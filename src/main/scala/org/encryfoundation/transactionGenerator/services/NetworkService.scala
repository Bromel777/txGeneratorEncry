package org.encryfoundation.transactionGenerator.services

import java.net.InetSocketAddress

import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, ContextShift, Resource, Sync}
import cats.implicits._
import fs2.Stream
import fs2.concurrent.{Queue, Topic}
import fs2.io.tcp.SocketGroup
import com.comcast.ip4s._
import io.chrisdavenport.log4cats.Logger
import org.encryfoundation.common.network.BasicMessagesRepo.NetworkMessage
import org.encryfoundation.transactionGenerator.network.{ConnectionHandler, SocketHandler}
import org.encryfoundation.transactionGenerator.network.Network.{dummyHandshake, processIncoming}
import org.encryfoundation.transactionGenerator.services.TransactionService.Message
import org.encryfoundation.transactionGenerator.utils.Casting

trait NetworkService[F[_]] {

  def connectTo(peer: SocketAddress[Ipv4Address]): F[Unit]
  def closeConnection(ip: SocketAddress[Ipv4Address]): F[Unit]
  def subscribe: Stream[F, Unit]
  def publish: Stream[F, NetworkMessage]
  def start(port: Port): Stream[F, Unit]
}

object NetworkService {

  private class Live[F[_] : Sync : Concurrent : ContextShift](socketGroupResource: Resource[F, SocketGroup],
                                                              logger: Logger[F],
                                                              connectedPeers: Ref[F, Map[SocketAddress[Ipv4Address], ConnectionHandler[F]]],
                                                              connectBuffer: Queue[F, SocketAddress[Ipv4Address]],
                                                              disconnectBuffer: Queue[F, ConnectionHandler[F]],
                                                              txServiceTopic: Topic[F, Message]) extends NetworkService[F] {

    override def connectTo(peer: SocketAddress[Ipv4Address]): F[Unit] = connectBuffer.enqueue1(peer)

    override def closeConnection(ip: SocketAddress[Ipv4Address]): F[Unit] = disconnectBuffer.dequeue1.flatMap(_.close())

    override val subscribe: Stream[F, Unit] = for {
      msg   <- txServiceTopic.subscribe(100)
      peers <- Stream.eval(connectedPeers.get)
      _     <- Stream.emits(peers.values.toList.map(_.write(Casting.castFromMessage2EncryNetMsg(msg))))
    } yield ()

    //todo: implement
    override val publish: Stream[F, NetworkMessage] = Stream.empty

    override def start(port: Port): Stream[F, Unit] = {

      val startServerProgram = for {
        socketGroup <- Stream.resource(socketGroupResource)
        _           <- Stream.eval(logger.info(s"Start server at port ${port}"))
        socketRes   <- socketGroup.server(new InetSocketAddress(port.value))
        socket      <- Stream.resource(socketRes)
        handler     <- Stream.eval(ConnectionHandler(socket, logger))
        handlers    <- processIncoming(handler, logger)
      } yield handlers

      val toConnectStream = for {
        peerToConnect <- connectBuffer.dequeue
        socketGroup   <- Stream.resource(socketGroupResource)
        socket        <- Stream.resource(socketGroup.client(peerToConnect.toInetSocketAddress))
        handler       <- Stream.eval(SocketHandler(socket, logger))
        _             <- Stream.eval(handler.write(dummyHandshake))
        msg           <- handler.read()
        _             <- Stream.eval(logger.info(s"get msg: $msg"))
      } yield ()

      (startServerProgram concurrently  (Stream.eval(connectTo(SocketAddress(ipv4"0.0.0.0", Port(9040).get))) ++ toConnectStream))
    }
  }

  def apply[F[_]: Sync : Concurrent : ContextShift](socketGroupResource: Resource[F, SocketGroup],
                                                    logger: Logger[F],
                                                    txTopic: Topic[F, Message]): F[NetworkService[F]] = for {
    connectBuffer <- Queue.bounded[F, SocketAddress[Ipv4Address]](100)
    disconnectBuffer <- Queue.bounded[F, ConnectionHandler[F]](100)
    peers <- Ref.of[F, Map[SocketAddress[Ipv4Address], ConnectionHandler[F]]](Map.empty)
  } yield (new Live(socketGroupResource, logger, peers, connectBuffer, disconnectBuffer, txTopic))
}
