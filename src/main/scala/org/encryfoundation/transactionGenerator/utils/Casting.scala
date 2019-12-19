package org.encryfoundation.transactionGenerator.utils

import org.encryfoundation.common.modifiers.mempool.transaction.{Transaction, TransactionProtoSerializer}
import org.encryfoundation.common.network.BasicMessagesRepo.{InvNetworkMessage, ModifiersNetworkMessage, NetworkMessage}
import org.encryfoundation.transactionGenerator.programs.TransactionProgram.Message
import org.encryfoundation.transactionGenerator.programs.TransactionProgram.Messages.TransactionForNetwork

object Casting {

  def castFromMessage2EncryNetMsgModifier(msg: Message): ModifiersNetworkMessage = msg match {
    case msgWithTx: TransactionForNetwork =>
      ModifiersNetworkMessage(
        Transaction.modifierTypeId,
        Map(msgWithTx.tx.id -> TransactionProtoSerializer.toProto(msgWithTx.tx).toByteArray)
      )
  }

  def castFromMessage2EncryNetMsgInv(msg: Message): InvNetworkMessage = msg match {
    case msgWithTx: TransactionForNetwork =>
      InvNetworkMessage(Transaction.modifierTypeId, List(msgWithTx.tx.id))
  }
}
