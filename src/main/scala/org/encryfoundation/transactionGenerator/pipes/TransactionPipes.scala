package org.encryfoundation.transactionGenerator.pipes

import cats.Applicative
import cats.effect.{Concurrent, Sync}
import fs2.Pipe
import org.encryfoundation.common.crypto.PrivateKey25519
import org.encryfoundation.common.modifiers.mempool.transaction.{Proof, Transaction}
import org.encryfoundation.common.modifiers.state.box.{EncryBox, MonetaryBox}
import org.encryfoundation.prismlang.compiler.CompiledContract
import org.encryfoundation.transactionGenerator.utils.TransactionsFactory

object TransactionPipes {

  def fromBx2Tx[F[_]: Concurrent](privateKey: PrivateKey25519,
                                  fee: Long): Pipe[F, MonetaryBox, Transaction] = is => is.filter(
    _.amount > fee
  ).parEvalMapUnordered(3){ (bx) =>
    Sync[F].delay(TransactionsFactory.defaultPaymentTransaction(
      privateKey,
      fee,
      System.currentTimeMillis(),
      List(bx -> Option.empty[(CompiledContract, Seq[Proof])]),
      privateKey.publicImage.address.address,
      bx.amount - fee,
      2
    ))
  }
}
