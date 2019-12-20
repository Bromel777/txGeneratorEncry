package org.encryfoundation.transactionGenerator

import cats.effect.{Concurrent, ConcurrentEffect, ContextShift, ExitCode, IO, IOApp, Resource, Sync, Timer}
import cats.implicits._
import com.comcast.ip4s.Port
import fs2.Stream
import fs2.concurrent.{Queue, Topic}
import io.chrisdavenport.log4cats.{Logger, SelfAwareStructuredLogger}
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.encryfoundation.common.modifiers.mempool.transaction.PubKeyLockedContract
import org.encryfoundation.common.network.BasicMessagesRepo.{NetworkMessage, SyncInfoNetworkMessage}
import org.encryfoundation.common.network.SyncInfo
import org.encryfoundation.common.utils.Algos
import org.encryfoundation.transactionGenerator.TestApp.{contractHash, privKey}
import org.encryfoundation.transactionGenerator.programs.TransactionProgram.Messages.Init
import org.encryfoundation.transactionGenerator.programs.{NetworkProgram, TransactionProgram}
import org.encryfoundation.transactionGenerator.settings.GeneratorSettings
import org.encryfoundation.transactionGenerator.utils.Mnemonic

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
                                       contractHash,
                                       privKey,
                                       100,
                                       config.loadSettings,
                                       netInQueue,
                                       netOutQueue
                                      )
      } yield (networkProgram, transactionGrabberProgram)
}

object TestApp extends IOApp {

  val mnemonic = "index another island accuse valid aerobic little absurd bunker keep insect scissors"
  val privKey = Mnemonic.createPrivKey(Option(mnemonic))
  val contractHash: String = Algos.encode(PubKeyLockedContract(privKey.publicImage.pubKeyBytes).contract.hash)

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
