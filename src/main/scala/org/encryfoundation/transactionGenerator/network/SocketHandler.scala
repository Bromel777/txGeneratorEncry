package org.encryfoundation.transactionGenerator.network

import cats.effect.{Concurrent, Sync}
import cats.implicits._
import fs2.Stream
import fs2.concurrent.Queue
import fs2.io.tcp.Socket
import io.chrisdavenport.log4cats.Logger
import org.encryfoundation.common.network.BasicMessagesRepo.NetworkMessage
import org.encryfoundation.transactionGenerator.utils.Serializer
import scala.concurrent.duration._

trait SocketHandler[F[_]] {

  def read(): Stream[F, NetworkMessage]
  def write(msg: NetworkMessage): F[Unit]
}

object SocketHandler {

  class Live[F[_]: Sync : Concurrent] (socket: Socket[F],
                                       msgQueue: Queue[F, NetworkMessage],
                                       logger: Logger[F]) extends SocketHandler[F] {

    private val readHandshake: Stream[F, NetworkMessage] = Stream.eval(socket.read(1024, Some(30 seconds))).flatMap {
      case Some(chunk) => Stream.chunk(chunk).through(Serializer.handshakeFromBytes(logger))
      case None => readHandshake
    }

    private val readAnotherMessages = {
      val readSocket = socket.reads(1024).through(Serializer.fromBytes(logger))
      val writeOutput = msgQueue.dequeue
        .through(Serializer.toBytes)
        .through(socket.writes(None)) >> Stream.eval(logger.info("Write to socket"))
      readSocket concurrently writeOutput
    }

    override def read(): Stream[F, NetworkMessage] = for {
      handshake <- readHandshake
      _         <- Stream.eval(logger.info(s"Got handshake: $handshake"))
      msg       <- readAnotherMessages
    } yield msg

    override def write(msg: NetworkMessage): F[Unit] =
      logger.info(s"add msg ${msg} to queue") *> msgQueue.enqueue1(msg)
  }

  def apply[F[_] : Sync : Concurrent](socket: Socket[F], logger: Logger[F]): F[SocketHandler[F]] = for {
    queue   <- Queue.bounded[F, NetworkMessage](100)
  } yield new Live[F](socket, queue, logger)
}
