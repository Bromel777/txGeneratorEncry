package org.encryfoundation.transactionGenerator

import cats.effect.{ConcurrentEffect, ContextShift, ExitCode, IO, IOApp, Sync, Timer}
import cats.implicits._
import com.comcast.ip4s.Port
import fs2.Stream
import fs2.concurrent.Queue
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.encryfoundation.common.network.BasicMessagesRepo.NetworkMessage
import org.encryfoundation.transactionGenerator.programs.{NetworkProgram, TransactionProgram}
import org.encryfoundation.transactionGenerator.settings.GeneratorSettings

final class TestApp[F[_]: ContextShift : Timer:
                      ConcurrentEffect : Logger] private (netInQueue: Queue[F, NetworkMessage],
                                                          netOutQueue: Queue[F, NetworkMessage],
                                                          config: GeneratorSettings) {

  val program = Stream.resource(subprograms).flatMap { case (netProg, txProg) =>
    netProg.start concurrently txProg.start
  }

  private def subprograms = for {
        networkProgram            <- NetworkProgram(
                                       config.networkSettings.peers,
                                       Port(config.networkSettings.bindPort).get,
                                       netOutQueue,
                                       netInQueue
                                     )
        transactionGrabberProgram <- TransactionProgram(
                                       100,
                                       config,
                                       netInQueue,
                                       netOutQueue
                                      )
      } yield (networkProgram, transactionGrabberProgram)
}

object TestApp extends IOApp {

  def apply[F[_] : ContextShift : Timer: ConcurrentEffect](): F[TestApp[F]] = for {
    netInQueue  <- Queue.bounded[F, NetworkMessage](1000)
    netOutQueue <- Queue.bounded[F, NetworkMessage](1000)
    config      <- Sync[F].delay(GeneratorSettings.loadConfig("application.conf"))
    logger      <- Slf4jLogger.create[F]
  } yield {
    implicit val log = logger
    new TestApp(netInQueue, netOutQueue, config)
  }

  override def run(args: List[String]): IO[ExitCode] = TestApp[IO]
    .flatMap(_.program.compile.drain as ExitCode.Success)
}
