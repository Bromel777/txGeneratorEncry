package com.github.bromel777.services

import cats.{Applicative, Monad}
import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, Timer}
import cats.kernel.Monoid
import org.encryfoundation.common.modifiers.mempool.transaction.Transaction
import fs2.Stream
import org.encryfoundation.common.modifiers.state.box.EncryBaseBox

import scala.concurrent.duration._

trait TransactionService[F[_]] {

  def startTransactionPublishing: Stream[F, Transaction]
}

object TransactionService {

  sealed trait Message
  object Messages {
    case class TransactionForNetwork(tx: Transaction) extends Message
  }

  private class Life[F[_]: Timer: Concurrent](explorerService: ExplorerService[F],
                                              startPoint: Ref[F, Int],
                                              contractHash: String,
                                              boxesQty: Int) extends TransactionService[F] {

    private def getNextBoxes: Stream[F, EncryBaseBox] = for {
      startPointVal <- Stream.eval(startPoint.get)
      boxesList     <- Stream.eval(explorerService.getBoxesInRange(contractHash, startPointVal, startPointVal + boxesQty))
      _             <- Stream.eval(
                         if (boxesList.length < boxesQty) startPoint.set(0)
                         else startPoint.set(startPointVal + boxesQty)
                       )
      boxes         <- Stream.emits(boxesList)
    } yield boxes

    override def startTransactionPublishing: Stream[F, Transaction] =
      Stream.awakeEvery[F](1.seconds).zipRight()
  }
}
