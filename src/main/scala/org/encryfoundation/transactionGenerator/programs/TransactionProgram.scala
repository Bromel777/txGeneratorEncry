package org.encryfoundation.transactionGenerator.programs

import cats.Applicative
import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, ConcurrentEffect, Resource, Timer}
import cats.implicits._
import fs2.Stream
import fs2.concurrent.Queue
import io.chrisdavenport.log4cats.Logger
import org.encryfoundation.common.modifiers.mempool.transaction.{PubKeyLockedContract, Transaction, TransactionProtoSerializer}
import org.encryfoundation.common.modifiers.state.box.{AssetBox, EncryBaseBox}
import org.encryfoundation.common.network.BasicMessagesRepo.{InvNetworkMessage, ModifiersNetworkMessage, NetworkMessage, RequestModifiersNetworkMessage}
import org.encryfoundation.common.utils.Algos
import org.encryfoundation.common.utils.TaggedTypes.ModifierId
import org.encryfoundation.transactionGenerator.pipes.TransactionPipes
import org.encryfoundation.transactionGenerator.services.ExplorerService
import org.encryfoundation.transactionGenerator.settings.GeneratorSettings
import org.encryfoundation.transactionGenerator.settings.GeneratorSettings.ContractHash
import org.encryfoundation.transactionGenerator.utils.Mnemonic

import scala.concurrent.duration._
import scala.reflect.ClassTag

trait TransactionProgram[F[_]] {

  def start: Stream[F, Unit]
}

object TransactionProgram {

  sealed trait Message
  object Messages {
    case class Init(txt: String) extends Message
    case class TransactionForNetwork(tx: Transaction) extends Message
  }

  private class Live[F[_]: Timer: Concurrent : Logger](startPointRefs: Ref[F, Map[ContractHash, Int]],
                                                       boxesQty: Int,
                                                       settings: GeneratorSettings,
                                                       txsMapRef: Ref[F, Map[ModifierId, Transaction]],
                                                       networkInMsgQueue: Queue[F, NetworkMessage],
                                                       networkOutMsgQueue: Queue[F, NetworkMessage],
                                                       explorerService: ExplorerService[F]) extends TransactionProgram[F] {

    private val keys = settings.walletSettings.mnemonicKeys.map(mnemonic => Mnemonic.createPrivKey(Option(mnemonic)))

    private val contractsHashes = keys.map(
      key => (ContractHash @@ Algos.encode(PubKeyLockedContract(key.publicImage.pubKeyBytes).contract.hash))
    )

    private val keysMap = (contractsHashes zip keys).toMap

    private val txsStream = Stream(())
      .repeat
      .covary[F]
      .metered((settings.loadSettings.tps.toInt) seconds)
      .evalMap(_ => newTxs(settings.loadSettings.tps.toInt))
      .handleErrorWith{ h => Stream.eval(Logger[F].warn(s"Error: ${h}. During txs sending pipeline"))}
      .onFinalize(Logger[F].info("txs stream ends"))

    private def newTxs(boxesQty: Int) = for {
      hashes <- contractsHashes.pure[F]
      bxs <- hashes.flatTraverse(hash => getBoxes[AssetBox](hash, boxesQty))
      txs <- bxs.filter(_.amount > 1).traverse(bx => TransactionPipes.fromBxToTx(keysMap, 1, bx))
      _   <- txs.traverse(sendInvForTx)
    } yield ()

    private def getBoxes[T <: EncryBaseBox: ClassTag](hash: ContractHash, boxesQty: Int): F[List[T]] = for {
      startPointsMap <- startPointRefs.get
      contractHashStartPoint <- startPointsMap.getOrElse(hash, 0).pure[F]
      allBoxes <- explorerService.getBoxesInRange(hash, contractHashStartPoint, contractHashStartPoint + boxesQty)
      acceptedBoxes <- allBoxes.collect{case bx: T => bx}.pure[F]
      _  <- startPointRefs.update(_.updated(hash, contractHashStartPoint + boxesQty))
      additionalBoxes <- (if (boxesQty > acceptedBoxes.length) getBoxes[T](hash, boxesQty - acceptedBoxes.length) else List.empty[T].pure[F])
    } yield acceptedBoxes ++ additionalBoxes

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
            Transaction.modifierTypeId -> Map((tx.id -> TransactionProtoSerializer.toProto(tx).toByteArray))
          )
        ) >> Logger[F].info("Tx found!")
      }

    private val addRequest: NetworkMessage => F[Unit] = {
      case RequestModifiersNetworkMessage((typeId, ids)) if typeId == Transaction.modifierTypeId =>
        ids.toList.traverse(addReqToTx) >> Applicative[F].unit
      case msg => Logger[F].warn(s"Got msg ${msg.messageName} from network")
    }

    private val responseStream = networkInMsgQueue.dequeue
      .evalMap(addRequest)
      .handleErrorWith(err => Stream.eval(Logger[F].warn(err)("Error occured during response stream in network prog")))
      .onFinalize(Logger[F].info("responseStream in tx prog ends!"))

    override val start: Stream[F, Unit] = responseStream concurrently txsStream

  }

  def apply[F[_]: Timer: Concurrent: Logger : ConcurrentEffect](boxesQty: Int,
                                                                settings: GeneratorSettings,
                                                                networkInMsgQueue: Queue[F, NetworkMessage],
                                                                networkOutMsgQueue: Queue[F, NetworkMessage]): Resource[F, TransactionProgram[F]] =
    ExplorerService[F](settings.explorerSettings).evalMap( explorerService =>
      for {
        startPointsMap <- Ref.of[F, Map[ContractHash, Int]](Map.empty[ContractHash, Int])
        txsMap         <- Ref.of[F, Map[ModifierId, Transaction]](Map.empty[ModifierId, Transaction])
      } yield new Live(
        startPointsMap,
        boxesQty,
        settings,
        txsMap,
        networkInMsgQueue,
        networkOutMsgQueue,
        explorerService
      )
    )
}
