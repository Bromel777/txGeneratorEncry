package org.encryfoundation.transactionGenerator.services

import cats.{Applicative, Monad}
import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, Sync, Timer}
import cats.kernel.Monoid
import TransactionService.Message
import TransactionService.Messages.TransactionForNetwork
import org.encryfoundation.common.modifiers.mempool.transaction.Transaction
import fs2.Stream
import fs2.concurrent.Topic
import org.encryfoundation.common.crypto.PrivateKey25519
import org.encryfoundation.common.modifiers.state.box.{AssetBox, EncryBaseBox}
import org.encryfoundation.transactionGenerator.pipes.TransactionPipes

import scala.concurrent.duration._

trait TransactionService[F[_]] {

  def startTransactionPublishing: Stream[F, Unit]
}

object TransactionService {

  sealed trait Message
  object Messages {
    case class Init(txt: String) extends Message
    case class TransactionForNetwork(tx: Transaction) extends Message
  }

  private class Live[F[_]: Timer: Concurrent](explorerService: ExplorerService[F],
                                              startPoint: Ref[F, Int],
                                              contractHash: String,
                                              privateKey: PrivateKey25519,
                                              boxesQty: Int,
                                              topicToReqAndResService: Topic[F, Message]) extends TransactionService[F] {

    private def getNextBoxes: Stream[F, AssetBox] = for {
      startPointVal <- Stream.eval(startPoint.get)
      boxesList     <- Stream.eval(explorerService.getBoxesInRange(contractHash, startPointVal, startPointVal + boxesQty))
      _             <- Stream.eval(
                         if (boxesList.length < boxesQty) startPoint.set(0)
                         else startPoint.set(startPointVal + boxesQty)
                       )
      boxes         <- Stream.emits(boxesList.map(_.asInstanceOf[AssetBox]))
    } yield boxes

    override def startTransactionPublishing: Stream[F, Unit] =
      topicToReqAndResService.publish(
        Stream.awakeEvery[F](10.seconds) zipRight getNextBoxes.through(TransactionPipes.fromBx2Tx(
          privateKey,
          1
        )).map(tx => TransactionForNetwork(tx))
      )
  }

  def apply[F[_]: Timer: Concurrent](explorerService: ExplorerService[F],
                                     startPoint: Ref[F, Int],
                                     contractHash: String,
                                     privateKey: PrivateKey25519,
                                     boxesQty: Int,
                                     topicToNetworkService: Topic[F, Message]): F[TransactionService[F]] =
    Sync[F].delay(new Live(
      explorerService,
      startPoint,
      contractHash,
      privateKey,
      boxesQty,
      topicToNetworkService
    ))
}
