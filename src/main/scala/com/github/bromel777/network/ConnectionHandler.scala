package com.github.bromel777.network

import cats.Applicative
import cats.implicits._
import cats.effect.{Concurrent, Resource, Sync}
import com.github.bromel777.utils.Serializer
import fs2.concurrent.Queue
import fs2.io.tcp._
import fs2.Stream
import io.chrisdavenport.log4cats.Logger
import org.encryfoundation.common.network.BasicMessagesRepo.NetworkMessage

class ConnectionHandler[F[_]: Sync : Concurrent] private(socket: Socket[F],
                                                         msgQueue: Queue[F, NetworkMessage],
                                                         logger: Logger[F]) {

  def close(): F[Unit] = socket.close
  def read(): Stream[F, NetworkMessage] = {
    val readSocket = socket.reads(1024).through(Serializer.fromBytes(logger))
    val writeOutput = msgQueue.dequeue
      .through(Serializer.toBytes)
      .through(socket.writes(None)) >> Stream.eval(logger.info("Write to socket"))
    readSocket concurrently writeOutput
  }
  def write(msg: NetworkMessage): F[Unit] =
    logger.info(s"add msg ${msg} to queue") *> msgQueue.enqueue1(msg)
}

object ConnectionHandler {
  def apply[F[_] : Sync : Concurrent](socket: Socket[F], logger: Logger[F]): F[ConnectionHandler[F]] = for {
    queue <- Queue.bounded[F, NetworkMessage](100)
  } yield new ConnectionHandler(socket, queue, logger)
}

