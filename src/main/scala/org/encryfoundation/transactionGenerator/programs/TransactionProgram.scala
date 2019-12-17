package org.encryfoundation.transactionGenerator.programs

import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, ConcurrentEffect, ContextShift, IO, Resource, Sync, Timer}
import cats.implicits._
import fs2.Stream
import fs2.concurrent.Topic
import io.chrisdavenport.log4cats.Logger
import org.encryfoundation.common.crypto.PrivateKey25519
import org.encryfoundation.common.modifiers.mempool.transaction.Transaction
import org.encryfoundation.common.modifiers.state.box.AssetBox
import org.encryfoundation.transactionGenerator.pipes.TransactionPipes
import org.encryfoundation.transactionGenerator.programs.TransactionProgram.Message
import org.encryfoundation.transactionGenerator.programs.TransactionProgram.Messages.TransactionForNetwork
import org.encryfoundation.transactionGenerator.services.ExplorerService
import org.encryfoundation.transactionGenerator.settings.GeneratorSettings.LoadSettings

import scala.concurrent.duration._

trait TransactionProgram[F[_]] {

  def start: Stream[F, Unit]
}

object TransactionProgram {

  sealed trait Message
  object Messages {
    case class Init(txt: String) extends Message
    case class TransactionForNetwork(tx: Transaction) extends Message
  }

  private class Live[F[_]: Timer: Concurrent : Logger](startPoint: Ref[F, Int],
                                                       contractHash: String,
                                                       privateKey: PrivateKey25519,
                                                       boxesQty: Int,
                                                       settings: LoadSettings,
                                                       topicToReqAndResService: Topic[F, Message],
                                                       explorerService: ExplorerService[F]) extends TransactionProgram[F] {

    private val getNextBoxes: Stream[F, AssetBox] = for {
      startPointVal <- Stream.eval(startPoint.get)
      boxesList     <- Stream.eval(explorerService.getBoxesInRange(contractHash, startPointVal, startPointVal + boxesQty))
      _             <- Stream.eval(
                         if (boxesList.length < boxesQty) startPoint.set(0)
                         else startPoint.set(startPointVal + boxesQty)
                       )
      _             <- Stream.eval(Logger[F].info(s"get ${boxesList.size} boxes"))
      boxes         <- Stream.emits(boxesList.map(_.asInstanceOf[AssetBox]))
    } yield boxes

    override val start: Stream[F, Unit] =
      topicToReqAndResService.publish(
        getNextBoxes.through(TransactionPipes.fromBx2Tx(
          privateKey,
          1
        )).map(tx => TransactionForNetwork(tx)).evalTap(tx => Logger[F].info(s"send tx: ${tx}. ${(1/settings.tps)}")).repeat.metered((1/settings.tps) seconds)
      ).handleErrorWith { err =>
        Stream.eval(Logger[F].error(s"Network service err ${err}")) >> Stream.empty
      }
  }

  def apply[F[_]: Timer: Concurrent: Logger : ConcurrentEffect](contractHash: String,
                                                                privateKey: PrivateKey25519,
                                                                boxesQty: Int,
                                                                settings: LoadSettings,
                                                                topicToReqAndResService: Topic[F, Message]): Resource[F, TransactionProgram[F]] =
    ExplorerService[F].evalMap( explorerService =>
      for {
        startPoint <- Ref.of[F, Int](0)
      } yield new Live(
        startPoint,
        contractHash,
        privateKey,
        boxesQty,
        settings,
        topicToReqAndResService,
        explorerService
      )
    )
}
