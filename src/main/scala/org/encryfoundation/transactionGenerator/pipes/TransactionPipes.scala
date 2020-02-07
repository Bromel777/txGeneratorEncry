package org.encryfoundation.transactionGenerator.pipes

import cats.effect.Concurrent
import fs2.Pipe
import org.encryfoundation.common.crypto.PrivateKey25519
import org.encryfoundation.common.modifiers.mempool.transaction.{Proof, Transaction}
import org.encryfoundation.common.modifiers.state.box.AssetBox
import org.encryfoundation.common.utils.Algos
import cats.implicits._
import org.encryfoundation.prismlang.compiler.CompiledContract
import org.encryfoundation.transactionGenerator.settings.GeneratorSettings.ContractHash
import org.encryfoundation.transactionGenerator.utils.TransactionsFactory

object TransactionPipes {

  def fromBxToTx[F[_]: Concurrent](keysMap: Map[ContractHash, PrivateKey25519],
                                   fee: Long): Pipe[F, AssetBox, Transaction] = in => {
    in.filter(_.amount > fee ).parEvalMapUnordered(4){ bx =>
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
  }
}
