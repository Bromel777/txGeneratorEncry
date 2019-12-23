package org.encryfoundation.transactionGenerator.pipes

import cats.Applicative
import cats.effect.{Concurrent, Sync}
import fs2.Pipe
import org.encryfoundation.common.crypto.PrivateKey25519
import org.encryfoundation.common.modifiers.mempool.transaction.{Proof, Transaction}
import org.encryfoundation.common.modifiers.state.box.{AssetBox, EncryBox, MonetaryBox}
import org.encryfoundation.prismlang.compiler.CompiledContract
import org.encryfoundation.transactionGenerator.utils.TransactionsFactory

object TransactionPipes {

  def fromBxToTx(privateKey: PrivateKey25519,
                 fee: Long,
                 bx: AssetBox): Transaction = TransactionsFactory.defaultPaymentTransaction(
    privateKey,
    fee,
    System.currentTimeMillis(),
    List(bx -> Option.empty[(CompiledContract, Seq[Proof])]),
    privateKey.publicImage.address.address,
    bx.amount - fee,
    2
  )
}
