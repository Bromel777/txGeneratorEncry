package org.encryfoundation.transactionGenerator.programs

import java.net.InetSocketAddress

import cats.effect.concurrent.Ref
import cats.effect.{Blocker, Concurrent, ContextShift, IO, Resource, Sync, Timer}
import cats.implicits._
import com.comcast.ip4s._
import fs2.Stream
import fs2.concurrent.{Queue, Topic}
import fs2.io.tcp.SocketGroup
import io.chrisdavenport.log4cats.Logger
import org.encryfoundation.common.network.BasicMessagesRepo.{Handshake, NetworkMessage}
import org.encryfoundation.transactionGenerator.services.SocketService

import scala.concurrent.duration._

trait NetworkProgram[F[_]] {

  def start: Stream[F, Unit]
}

object NetworkProgram {

  val dummyHandshake = Handshake(protocolToBytes("0.9.3"),
    "test node",
    Some(SocketAddress(ipv4"192.168.30.104", Port(1234).get).toInetSocketAddress),
    1575975624532L)

  def protocolToBytes(protocol: String) = protocol.split("\\.").map(elem => elem.toByte)

  private class Live[F[_] : Sync : Concurrent : ContextShift : Timer : Logger]
                    (socketGroup: SocketGroup,
                     connectedPeers: Ref[F, Map[SocketAddress[Ipv4Address], SocketService[F]]],
                     connectBuffer: Queue[F, SocketAddress[Ipv4Address]],
                     disconnectBuffer: Queue[F, SocketService[F]],
                     port: Port,
                     networkOutTopic: Topic[F, NetworkMessage],
                     networkInTopic: Topic[F, NetworkMessage]) extends NetworkProgram[F] {

    private def connectTo(peer: SocketAddress[Ipv4Address]): F[Unit] = connectBuffer.enqueue1(peer)

    private def closeConnection(ip: SocketAddress[Ipv4Address]): F[Unit] =
      disconnectBuffer.dequeue1.flatMap(_.close)

    private def subscribe(networkOutTopic: Topic[F, NetworkMessage]): Stream[F, Unit] = for {
      msg       <- networkOutTopic.subscribe(100)
      _         <- Stream.eval(Logger[F].info(s"get msg from topic: ${msg}"))
      peers     <- Stream.eval(connectedPeers.get)
      _         <- Stream.eval(Logger[F].info(s"peers: ${peers}"))
      peerRes   <- Stream.emits(peers.values.toList)
      _         <- Stream.eval(peerRes.write(msg))
    } yield ()

    //todo: implement
    private val publish: Stream[F, NetworkMessage] = Stream.empty

    override def start: Stream[F, Unit] = {

      val startServerProgram = for {
        _           <- Stream.eval(Logger[F].info(s"Start server at port ${port}"))
        _           <- socketGroup.server(new InetSocketAddress(port.value))
      } yield ()

      val toConnectStream = (for {
        peerToConnect <- connectBuffer.dequeue
        _             <- Stream.eval(Logger[F].info(s"Connect to ${peerToConnect}"))
        handlerRes    <- Stream.resource(SocketService(socketGroup, peerToConnect))
        _             <- Stream.eval(handlerRes.write(dummyHandshake))
        _             <- Stream.eval(connectedPeers.update(_.updated(peerToConnect, handlerRes)))
        msg           <- handlerRes.read
        _             <- Stream.eval(Logger[F].info(s"get msg: $msg"))
        _             <- Stream.eval(networkInTopic.publish1(msg))
      } yield ())

      (startServerProgram concurrently toConnectStream.repeat.metered(5 seconds) concurrently subscribe(networkOutTopic)).handleErrorWith { err =>
        Stream.eval(Logger[F].error(s"Network service err ${err}")) >> Stream.empty
      }
    }
  }

  def apply[F[_]: Concurrent : ContextShift : Timer: Logger](initPeers: List[SocketAddress[Ipv4Address]],
                                                             port: Port,
                                                             networkOutTopic: Topic[F, NetworkMessage],
                                                             networkInTopic: Topic[F, NetworkMessage]): Resource[F, NetworkProgram[F]] =
    Blocker[F].flatMap { blocker =>
      SocketGroup[F](blocker).evalMap { socketGroup =>
        for {
          connectBuffer <- Queue.bounded[F, SocketAddress[Ipv4Address]](100)
          _             <- initPeers.traverse(connectBuffer.enqueue1)
          disconnectBuffer <- Queue.bounded[F, SocketService[F]](100)
          peers <- Ref.of[F, Map[SocketAddress[Ipv4Address], SocketService[F]]](Map.empty)
        } yield (new Live(socketGroup, peers, connectBuffer, disconnectBuffer, port, networkOutTopic, networkInTopic))
      }
    }

}
