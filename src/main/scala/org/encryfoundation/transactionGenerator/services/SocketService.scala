package org.encryfoundation.transactionGenerator.services

import cats.effect.{Concurrent, ContextShift, Resource, Sync}
import cats.implicits._
import com.comcast.ip4s.{Ipv4Address, SocketAddress}
import fs2.Stream
import fs2.concurrent.Queue
import fs2.io.tcp.{Socket, SocketGroup}
import io.chrisdavenport.log4cats.Logger
import org.encryfoundation.common.network.BasicMessagesRepo.NetworkMessage
import org.encryfoundation.transactionGenerator.utils.Serializer

import scala.concurrent.duration._

trait SocketService[F[_]] {
  def read: Stream[F, NetworkMessage]
  def write(msg: NetworkMessage): F[Unit]
  def close: F[Unit]
}

object SocketService {

  class Live[F[_]: Sync : Concurrent : Logger](socket: Socket[F],
                                               msgQueue: Queue[F, NetworkMessage]) extends SocketService[F] {

    private val readHandshake: Stream[F, NetworkMessage] = Stream.eval(socket.read(1024, Some(30 seconds))).flatMap {
      case Some(chunk) => Stream.chunk(chunk).through(Serializer.handshakeFromBytes)
      case None => readHandshake
    }

    private val readAnotherMessages = {
      val readSocket = socket.reads(1024).through(Serializer.fromBytes)
      val writeOutput = msgQueue.dequeue
        .through(Serializer.toBytes)
        .through(socket.writes(None)) >> Stream.eval(Logger[F].info("Write to socket"))
      readSocket concurrently writeOutput
    }

    override val read: Stream[F, NetworkMessage] = for {
      handshake <- readHandshake
      _         <- Stream.eval(Logger[F].info(s"Got handshake: $handshake"))
      msg       <- readAnotherMessages
    } yield msg

    override def write(msg: NetworkMessage): F[Unit] =
      Logger[F].info(s"add msg ${msg} to queue") *> msgQueue.enqueue1(msg)

    override def close: F[Unit] = socket.close
  }

  def apply[F[_] : Sync : Concurrent : ContextShift : Logger](serverGroup: SocketGroup,
                                                              peerIp: SocketAddress[Ipv4Address]): Resource[F, SocketService[F]] =
    serverGroup.client(peerIp.toInetSocketAddress).flatMap { socket =>
      Resource.make[F, SocketService[F]](
        Queue.bounded[F, NetworkMessage]( 100 ).map { queue =>
          new Live[F](socket, queue)
        })( _.close )
    }
}
