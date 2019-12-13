package org.encryfoundation.transactionGenerator.utils

import org.encryfoundation.common.modifiers.mempool.transaction.Transaction
import org.encryfoundation.common.network.BasicMessagesRepo.{ModifiersNetworkMessage, NetworkMessage}
import org.encryfoundation.transactionGenerator.services.TransactionService.Message
import org.encryfoundation.transactionGenerator.services.TransactionService.Messages.TransactionForNetwork

object Casting {

  def castFromMessage2EncryNetMsg(msg: Message): NetworkMessage = msg match {
    case msgWithTx: TransactionForNetwork =>
      ModifiersNetworkMessage(Transaction.modifierTypeId, Map(msgWithTx.tx.id -> msgWithTx.tx.bytes))
  }
}
