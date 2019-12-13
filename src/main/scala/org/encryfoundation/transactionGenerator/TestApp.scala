package org.encryfoundation.transactionGenerator

import java.util.concurrent.Executors

import cats.effect.concurrent.Ref
import cats.effect.{Blocker, Concurrent, ExitCode, IO, IOApp, Sync}
import cats.implicits._
import com.comcast.ip4s.Port
import org.encryfoundation.transactionGenerator.services.{ExplorerService, NetworkService, RequestAndResponseService, TransactionService}
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
import org.encryfoundation.common.network.BasicMessagesRepo.{NetworkMessage, SyncInfoNetworkMessage}
import org.encryfoundation.common.network.SyncInfo
import org.encryfoundation.common.utils.Algos
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
      txsTopic <- Stream.eval(Topic[IO, TransactionService.Message](Init("Initial Event")))
      netInTopic <- Stream.eval(Topic[IO, NetworkMessage](SyncInfoNetworkMessage(SyncInfo(List.empty))))
      netOutTopic <- Stream.eval(Topic[IO, NetworkMessage](SyncInfoNetworkMessage(SyncInfo(List.empty))))
      networkService <- Stream.eval(NetworkService(sockets, logger, netOutTopic, netInTopic))
      contextForExplorer <- Stream.emit(ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(5)))
      client  <- BlazeClientBuilder[IO](contextForExplorer).stream
      explorerService <- Stream.eval(ExplorerService(client, logger))
      startPoint <- Stream.eval(Ref.of[IO, Int](0))
      txService <- Stream.eval(TransactionService(explorerService, startPoint, contractHash, privKey, 100, txsTopic))
      reqAndResService <- Stream.eval(RequestAndResponseService(txsTopic, netInTopic, netOutTopic, logger))
      _ <- networkService.start(Port(1234).get) concurrently reqAndResService.start concurrently txService.startTransactionPublishing
    } yield ()


  override def run(args: List[String]): IO[ExitCode] =
    (program).compile.drain.as(ExitCode.Success)
}

class First[F[_]: Concurrent](ref: Ref[F, Int]) {

}
