package org.encryfoundation.transactionGenerator.pipes

import cats.effect.Concurrent
import cats.implicits._
import org.encryfoundation.common.crypto.PrivateKey25519
import org.encryfoundation.common.modifiers.mempool.transaction.{Proof, Transaction}
import org.encryfoundation.common.modifiers.state.box.AssetBox
import org.encryfoundation.common.utils.Algos
import org.encryfoundation.prismlang.compiler.CompiledContract
import org.encryfoundation.transactionGenerator.settings.GeneratorSettings.ContractHash
import org.encryfoundation.transactionGenerator.utils.TransactionsFactory

object TransactionPipes {

  def fromBxToTx[F[_]: Concurrent](keysMap: Map[ContractHash, PrivateKey25519],
                                   fee: Long,
                                   bx: AssetBox): F[Transaction] =
    TransactionsFactory.defaultPaymentTransaction(
      keysMap( ContractHash @@ Algos.encode(bx.proposition.contractHash)),
        fee,
      System.currentTimeMillis(),
      List( bx -> Option.empty[(CompiledContract, Seq[Proof])] ),
      keysMap( ContractHash @@ Algos.encode(bx.proposition.contractHash)).publicImage.address.address,
      bx.amount - fee,
      2
    ).pure[F]
}
