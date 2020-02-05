package org.encryfoundation.transactionGenerator.programs

import cats.Applicative
import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, ConcurrentEffect, Resource, Sync, Timer}
import cats.implicits._
import fs2.Stream
import fs2.concurrent.Queue
import io.chrisdavenport.log4cats.Logger
import org.encryfoundation.common.crypto.PrivateKey25519
import org.encryfoundation.common.modifiers.mempool.transaction.Transaction
import org.encryfoundation.common.modifiers.state.box.AssetBox
import org.encryfoundation.common.network.BasicMessagesRepo.{InvNetworkMessage, ModifiersNetworkMessage, NetworkMessage, RequestModifiersNetworkMessage}
import org.encryfoundation.common.utils.Algos
import org.encryfoundation.common.utils.TaggedTypes.ModifierId
import org.encryfoundation.transactionGenerator.pipes.TransactionPipes
import org.encryfoundation.transactionGenerator.services.ExplorerService
import org.encryfoundation.transactionGenerator.settings.GeneratorSettings.{ExplorerSettings, LoadSettings}

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

  private class Live[F[_]: Timer: Concurrent : Logger](startPointRef: Ref[F, Int],
                                                       contractHash: String,
                                                       privateKey: PrivateKey25519,
                                                       boxesQty: Int,
                                                       loadSettings: LoadSettings,
                                                       explorerSettings: ExplorerSettings,
                                                       txsMapRef: Ref[F, Map[ModifierId, Transaction]],
                                                       networkInMsgQueue: Queue[F, NetworkMessage],
                                                       networkOutMsgQueue: Queue[F, NetworkMessage],
                                                       explorerService: ExplorerService[F]) extends TransactionProgram[F] {

    private val nextTxs = for {
      startPoint <- startPointRef.get
      boxes      <- explorerService.getBoxesInRange(contractHash, startPoint, startPoint + boxesQty)
      _          <- if (boxes.length < boxesQty) startPointRef.set(0)
                    else startPointRef.set(startPoint + boxesQty)
    } yield (boxes.collect { case bx: AssetBox if bx.amount > 1 =>
      TransactionPipes.fromBxToTx(
        privateKey,
        1,
        bx.asInstanceOf[AssetBox] )
    })

    private val addReqToTx: ModifierId => F[Unit] = id => for {
      txsMap <- txsMapRef.get
      _      <- Logger[F].info(s"Try to find tx with id: ${Algos.encode(id)}")
      _     <-  addTxToOutcomingQueue(txsMap, id)
    } yield ()

    private def sendInvForTx(tx: Transaction): F[Unit] =
      txsMapRef.update(_ + (tx.id -> tx)) >> networkOutMsgQueue.enqueue1(
        InvNetworkMessage(Transaction.modifierTypeId, List(tx.id))
      )

    private def addTxToOutcomingQueue(txsMap: Map[ModifierId, Transaction], txId: ModifierId): F[Option[Unit]] =
      txsMap.find(_._1 sameElements txId).traverse { case (_, tx) =>
        networkOutMsgQueue.enqueue1(
          ModifiersNetworkMessage(
            Transaction.modifierTypeId -> Map((tx.id -> tx.bytes))
          )
        ) >> Logger[F].info("Tx found!")
      }

    private val addRequest: NetworkMessage => F[Unit] = {
      case RequestModifiersNetworkMessage((typeId, ids)) if typeId == Transaction.modifierTypeId =>
        ids.toList.traverse(addReqToTx) >> Applicative[F].unit
      case msg => Logger[F].warn(s"Got msg ${msg} from network")
    }

    private val responseStream = networkInMsgQueue.dequeue.evalMap(addRequest)

    private val txsStream = Stream.evals(nextTxs)
      .evalMap(sendInvForTx).repeat
      .metered((1 / loadSettings.tps) seconds)

    override val start: Stream[F, Unit] = responseStream concurrently txsStream

  }

  def apply[F[_]: Timer: Concurrent: Logger : ConcurrentEffect](contractHash: String,
                                                                privateKey: PrivateKey25519,
                                                                boxesQty: Int,
                                                                loadSettings: LoadSettings,
                                                                explorerSettings: ExplorerSettings,
                                                                networkInMsgQueue: Queue[F, NetworkMessage],
                                                                networkOutMsgQueue: Queue[F, NetworkMessage]): Resource[F, TransactionProgram[F]] =
    ExplorerService[F](explorerSettings).evalMap( explorerService =>
      for {
        startPoint <- Ref.of[F, Int](0)
        txsMap     <- Ref.of[F, Map[ModifierId, Transaction]](Map.empty[ModifierId, Transaction])
      } yield new Live(
        startPoint,
        contractHash,
        privateKey,
        boxesQty,
        loadSettings,
        explorerSettings,
        txsMap,
        networkInMsgQueue,
        networkOutMsgQueue,
        explorerService
      )
    )
}
