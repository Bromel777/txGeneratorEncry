package com.github.bromel777.pipes

import cats.Applicative
import fs2.Pipe
import org.encryfoundation.common.modifiers.mempool.transaction.Transaction
import org.encryfoundation.common.modifiers.state.box.EncryBox

object TransactionPipes {

  def fromBx2Tx[F[_]: Applicative]: Pipe[F, EncryBox, Transaction] = is => is.parEvalMapUnordered(3){ (bx) =>
    Applicative[F].pure()
  }
}
