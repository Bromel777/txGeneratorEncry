package org.encryfoundation.transactionGenerator.programs

import java.net.InetSocketAddress

import cats.effect.concurrent.Ref
import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Timer}
import cats.implicits._
import com.comcast.ip4s._
import fs2.Stream
import fs2.concurrent.Queue
import fs2.io.tcp.SocketGroup
import io.chrisdavenport.log4cats.Logger
import org.encryfoundation.common.network.BasicMessagesRepo.{Handshake, NetworkMessage}
import org.encryfoundation.transactionGenerator.services.SocketService

trait NetworkProgram[F[_]] {

  def start: Stream[F, Unit]
}

object NetworkProgram {

  val dummyHandshake = Handshake(protocolToBytes("0.9.3"),
    "test node",
    Some(SocketAddress(ipv4"192.168.30.104", Port(1234).get).toInetSocketAddress),
    1575975624532L)

  def protocolToBytes(protocol: String) = protocol.split("\\.").map(elem => elem.toByte)

  private class Live[F[_] : Concurrent : ContextShift : Timer : Logger]
                    (socketGroup: SocketGroup,
                     connectedPeers: Ref[F, Map[SocketAddress[Ipv4Address], SocketService[F]]],
                     connectBuffer: Queue[F, SocketAddress[Ipv4Address]],
                     disconnectBuffer: Queue[F, SocketService[F]],
                     port: Port,
                     networkOutMsgQueue: Queue[F, NetworkMessage],
                     networkInMsgQueue: Queue[F, NetworkMessage]) extends NetworkProgram[F] {

    private val subscribe: Stream[F, Unit] = for {
      msg       <- networkOutMsgQueue.dequeue
      _         <- Stream.eval(Logger[F].info(s"Should write to network: ${msg}"))
      peers     <- Stream.eval(connectedPeers.get)
      _         <- Stream.eval(Logger[F].info(s"peers: ${peers}"))
      peerRes   <- Stream.emits(peers.values.toList)
      _         <- Stream.eval(peerRes.write(msg))
    } yield ()

    //todo: implement
    private val publish: Stream[F, NetworkMessage] = Stream.empty

    private val startServer = for {
      _           <- Stream.eval(Logger[F].info(s"Start server at port ${port}"))
      _           <- socketGroup.server(new InetSocketAddress(port.value))
    } yield ()

    private def handleConnection(handlerRes: Resource[F, SocketService[F]], peerToConnect: SocketAddress[Ipv4Address]): Stream[F, Unit] = (for {
      handlerRes    <- Stream.resource(handlerRes)
      _             <- Stream.eval(handlerRes.write(dummyHandshake))
      _             <- Stream.eval(connectedPeers.update(_.updated(peerToConnect, handlerRes)))
      msg           <- handlerRes.read
      _             <- Stream.eval(Logger[F].info(s"get msg: ${msg.messageName}"))
    } yield msg)
      .through(networkInMsgQueue.enqueue)
      .handleErrorWith(
        e => Stream.eval(
          Logger[F].error(e)(s"Error occurred during working with node ${peerToConnect}")
        )
      ).onFinalize(Logger[F].warn(s"Delete peer ${peerToConnect} from peers list") >> connectedPeers.update(map => map - peerToConnect))

    private val connect = (for {
      peerToConnect <- connectBuffer.dequeue
      _             <- Stream.eval(Logger[F].info(s"Connect to ${peerToConnect}"))
    } yield handleConnection(SocketService(socketGroup, peerToConnect), peerToConnect))
      .parJoinUnbounded

    override def start: Stream[F, Unit] = {
      startServer concurrently connect concurrently subscribe
      }.handleErrorWith { err =>
      Stream.eval( Logger[F].error( s"Network service err ${err}" ) ) >> Stream.empty
    }
  }

  def apply[F[_]: Concurrent : ContextShift : Timer: Logger](initPeers: List[SocketAddress[Ipv4Address]],
                                                             port: Port,
                                                             networkOutQueue: Queue[F, NetworkMessage],
                                                             networkInQueue: Queue[F, NetworkMessage]): Resource[F, NetworkProgram[F]] =
    Blocker[F].flatMap { blocker =>
      SocketGroup[F](blocker).evalMap { socketGroup =>
        for {
          connectBuffer <- Queue.bounded[F, SocketAddress[Ipv4Address]](100)
          _             <- initPeers.traverse(elem => connectBuffer.enqueue1(elem) >> Logger[F].info("test")) >> Logger[F].info(s"${initPeers} peers!")
          disconnectBuffer <- Queue.bounded[F, SocketService[F]](100)
          peers <- Ref.of[F, Map[SocketAddress[Ipv4Address], SocketService[F]]](Map.empty)
        } yield (new Live(socketGroup, peers, connectBuffer, disconnectBuffer, port, networkOutQueue, networkInQueue))
      }
    }

}
