package org.encryfoundation.transactionGenerator.services

import java.net.InetSocketAddress

import cats.MonadError
import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, ContextShift, Resource, Sync}
import cats.implicits._
import com.comcast.ip4s._
import fs2.Stream
import fs2.concurrent.{Queue, Topic}
import fs2.io.tcp.SocketGroup
import io.chrisdavenport.log4cats.Logger
import org.encryfoundation.common.network.BasicMessagesRepo.{Handshake, NetworkMessage}
import org.encryfoundation.transactionGenerator.network.SocketHandler

trait NetworkService[F[_]] {

  def connectTo(peer: SocketAddress[Ipv4Address]): F[Unit]
  def closeConnection(ip: SocketAddress[Ipv4Address]): F[Unit]
  def subscribe: Stream[F, Unit]
  def publish: Stream[F, NetworkMessage]
  def start(port: Port): Stream[F, Unit]
}

object NetworkService {

  val dummyHandshake = Handshake(protocolToBytes("0.9.3"),
    "test node",
    Some(SocketAddress(ipv4"192.168.30.104", Port(1234).get).toInetSocketAddress),
    1575975624532L)

  def protocolToBytes(protocol: String) = protocol.split("\\.").map(elem => elem.toByte)

  private class Live[F[_] : Sync : Concurrent : ContextShift : MonadError[*[_], Throwable]]
                    (socketGroupResource: Resource[F, SocketGroup],
                     logger: Logger[F],
                     connectedPeers: Ref[F, Map[SocketAddress[Ipv4Address], SocketHandler[F]]],
                     connectBuffer: Queue[F, SocketAddress[Ipv4Address]],
                     disconnectBuffer: Queue[F, SocketHandler[F]],
                     networkOutTopic: Topic[F, NetworkMessage],
                     networkInTopic: Topic[F, NetworkMessage]) extends NetworkService[F] {

    override def connectTo(peer: SocketAddress[Ipv4Address]): F[Unit] = connectBuffer.enqueue1(peer)

    override def closeConnection(ip: SocketAddress[Ipv4Address]): F[Unit] =
      disconnectBuffer.dequeue1.flatMap(_.close)

    override val subscribe: Stream[F, Unit] = for {
      msg   <- networkOutTopic.subscribe(100)
      _     <- Stream.eval(logger.info(s"get msg from topic: ${msg}"))
      peers <- Stream.eval(connectedPeers.get)
      _     <- Stream.eval(logger.info(s"peers: ${peers}"))
      peer  <- Stream.emits(peers.values.toList)
      _     <- Stream.eval(peer.write(msg))
    } yield ()

    //todo: implement
    override val publish: Stream[F, NetworkMessage] = Stream.empty

    override def start(port: Port): Stream[F, Unit] = {

      val startServerProgram = for {
        socketGroup <- Stream.resource(socketGroupResource)
        _           <- Stream.eval(logger.info(s"Start server at port ${port}"))
        _           <- socketGroup.server(new InetSocketAddress(port.value))
      } yield ()

      val toConnectStream = (for {
        peerToConnect <- connectBuffer.dequeue
        _             <- Stream.eval(logger.info(s"Connect to ${peerToConnect}"))
        socketGroup   <- Stream.resource(socketGroupResource)
        socket        <- Stream.resource(socketGroup.client(peerToConnect.toInetSocketAddress))
        handler       <- Stream.eval(SocketHandler(socket, logger))
        _             <- Stream.eval(handler.write(dummyHandshake))
        _             <- Stream.eval(connectedPeers.update(_.updated(peerToConnect, handler)))
        msg           <- handler.read()
        _             <- Stream.eval(logger.info(s"get msg: $msg"))
        _             <- Stream.eval(networkInTopic.publish1(msg))
      } yield ()).handleErrorWith { err =>
        Stream.eval(logger.info(s"Got error in toConnect stream: ${err}")) >> Stream.empty
      }

      (startServerProgram concurrently (Stream.eval(connectTo(SocketAddress(ipv4"172.16.11.14", Port(9040).get))) ++ toConnectStream)
        concurrently subscribe)
    }
  }

  def apply[F[_]: Sync : Concurrent : ContextShift](socketGroupResource: Resource[F, SocketGroup],
                                                    logger: Logger[F],
                                                    networkOutTopic: Topic[F, NetworkMessage],
                                                    networkInTopic: Topic[F, NetworkMessage]): F[NetworkService[F]] = for {
    connectBuffer <- Queue.bounded[F, SocketAddress[Ipv4Address]](100)
    disconnectBuffer <- Queue.bounded[F, SocketHandler[F]](100)
    peers <- Ref.of[F, Map[SocketAddress[Ipv4Address], SocketHandler[F]]](Map.empty)
  } yield (new Live(socketGroupResource, logger, peers, connectBuffer, disconnectBuffer, networkOutTopic, networkInTopic))
}
