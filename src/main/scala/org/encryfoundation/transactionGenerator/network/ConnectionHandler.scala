package org.encryfoundation.transactionGenerator.network

import cats.Applicative
import cats.implicits._
import cats.effect.{Concurrent, Resource, Sync}
import org.encryfoundation.common.network.BasicMessagesRepo.Handshake
import org.encryfoundation.transactionGenerator.network.Network.dummyHandshake
import org.encryfoundation.transactionGenerator.utils.Serializer
import fs2.concurrent.Queue
import fs2.io.tcp._
import fs2.Stream
import com.comcast.ip4s._
import io.chrisdavenport.log4cats.Logger
import org.encryfoundation.common.network.BasicMessagesRepo.NetworkMessage
import org.encryfoundation.transactionGenerator.services.TransactionService.Message
import org.encryfoundation.transactionGenerator.utils.Serializer

import scala.concurrent.duration._

class ConnectionHandler[F[_]: Sync : Concurrent] private(socket: Socket[F],
                                                         msgQueue: Queue[F, NetworkMessage],
                                                         logger: Logger[F]) {

  def close(): F[Unit] = socket.close
  def readHandshake(): Stream[F, NetworkMessage] = Stream.eval(socket.read(1024, Some(30 seconds))).flatMap {
    case Some(chunk) => Stream.chunk(chunk).through(Serializer.handshakeFromBytes(logger))
    case None => readHandshake()
  }
  def read(): Stream[F, NetworkMessage] = {
    val readSocket = socket.reads(1024).through(Serializer.fromBytes(logger))
    val writeOutput = msgQueue.dequeue
      .through(Serializer.toBytes)
      .through(socket.writes(None)) >> Stream.eval(logger.info("Write to socket"))
    readSocket concurrently writeOutput
  }
  def write(msg: NetworkMessage): F[Unit] =
    logger.info(s"add msg ${msg} to queue") *> msgQueue.enqueue1(msg)

  def startIteration: F[Unit] = write(dummyHandshake)
}

object ConnectionHandler {
  def apply[F[_] : Sync : Concurrent](socket: Socket[F], logger: Logger[F]): F[ConnectionHandler[F]] = for {
    queue   <- Queue.bounded[F, NetworkMessage](100)
    handler = new ConnectionHandler(socket, queue, logger)
    _       <- handler.startIteration
  } yield handler
}

