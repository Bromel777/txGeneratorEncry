package org.encryfoundation.transactionGenerator

import java.util.concurrent.Executors

import cats.effect.concurrent.Ref
import cats.effect.{Blocker, Concurrent, ExitCode, IO, IOApp, Sync}
import cats.implicits._
import com.comcast.ip4s.Port
import org.encryfoundation.transactionGenerator.network.Network
import org.encryfoundation.transactionGenerator.services.{ExplorerService, NetworkService, TransactionService}
import org.encryfoundation.transactionGenerator.utils.Mnemonic
import fs2.Stream
import fs2.concurrent.Topic
import fs2.io.tcp.SocketGroup
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.circe.Decoder
import jawnfs2._
import org.encryfoundation.common.modifiers.mempool.transaction.PubKeyLockedContract
import org.encryfoundation.common.modifiers.state.box.EncryBaseBox
import org.encryfoundation.common.utils.Algos
import org.encryfoundation.transactionGenerator.network.Network
import org.encryfoundation.transactionGenerator.services.TransactionService.Messages.Init
import org.encryfoundation.transactionGenerator.utils.Mnemonic
import org.http4s._
import org.http4s.client.blaze._

import scala.concurrent.ExecutionContext

object TestApp extends IOApp {

  val mnemonic = "index another island accuse valid aerobic little absurd bunker keep insect scissors"
  val privKey = Mnemonic.createPrivKey(Option(mnemonic))
  val contractHash: String = Algos.encode(PubKeyLockedContract(privKey.publicImage.pubKeyBytes).contract.hash)


  val sockets = for {
      blocker     <- Blocker[IO]
      socketGroup <- SocketGroup[IO](blocker)
    } yield socketGroup

  val program = for {
      logger <- Stream.eval(Slf4jLogger.create[IO])
      dummyTopic <- Stream.eval(Topic[IO, TransactionService.Message](Init("Initial Event")))
      networkService <- Stream.eval(NetworkService(sockets, logger, dummyTopic))
      _ <- networkService.start(Port(1234).get)
    } yield ()

//  val reqProg =
//    for {
//      context <- Stream.emit(ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(5)))
//      logger  <- Stream.eval(Slf4jLogger.create[IO])
//      client  <- BlazeClientBuilder[IO](context).stream
//      service <- Stream.eval(ExplorerService(client, logger))
//      boxes   <- service.getBoxesInRange(contractHash, 0, 100).evalTap()
//    } yield (boxes)


  override def run(args: List[String]): IO[ExitCode] =
    (program).compile.drain.as(ExitCode.Success)
}

class First[F[_]: Concurrent](ref: Ref[F, Int]) {

}
