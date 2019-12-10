package com.github.bromel777

import cats.Applicative
import cats.effect.concurrent.Ref
import cats.effect.{Blocker, Concurrent, ContextShift, ExitCode, IO, IOApp, Sync}
import fs2.{Chunk, Pipe, Pull, Stream}
import cats.implicits._
import com.comcast.ip4s.Port
import com.github.bromel777.network.Network
import fs2.io.tcp.SocketGroup
import io.chrisdavenport.log4cats.SelfAwareStructuredLogger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import scorex.utils.Random

import scala.collection.immutable

object TestApp extends IOApp {

  val sockets = for {
      blocker     <- Blocker[IO]
      socketGroup <- SocketGroup[IO](blocker)
    } yield socketGroup

  val program = for {
      logger <- Stream.eval(Slf4jLogger.create[IO])
      _ <- Network.startServer(Port(1234).get, sockets, logger)
    } yield ()

  override def run(args: List[String]): IO[ExitCode] = program.compile.drain.as(ExitCode.Success)
}

class First[F[_]: Concurrent](ref: Ref[F, Int]) {

}
