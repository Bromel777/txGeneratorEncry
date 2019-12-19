package org.encryfoundation.transactionGenerator.programs

import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, Timer}
import cats.implicits._
import fs2.Stream
import fs2.concurrent.{Queue, Topic}
import io.chrisdavenport.log4cats.Logger
import org.encryfoundation.common.network.BasicMessagesRepo.{NetworkMessage, RequestModifiersNetworkMessage}
import org.encryfoundation.common.utils.Algos
import org.encryfoundation.common.utils.TaggedTypes.ModifierId
import org.encryfoundation.transactionGenerator.programs.TransactionProgram.{Message, Messages}
import org.encryfoundation.transactionGenerator.utils.Casting

import scala.concurrent.duration._

trait RequestAndResponseProgram[F[_]] {

  def start: Stream[F, Unit]
}

object RequestAndResponseProgram {

  private class Live[F[_]: Concurrent : Timer: Logger](networkOutBuffer: Queue[F, NetworkMessage],
                                                       cacheRef: Ref[F, Map[ModifierId, Message]],
                                                       transactionTopic: Topic[F, Message],
                                                       networkInTopic: Topic[F, NetworkMessage],
                                                       networkOutTopic: Topic[F, NetworkMessage]) extends RequestAndResponseProgram[F] {

    override def start: Stream[F, Unit] = {
      val networkTopicSubscriber = for {
        msgFromNetwork <- networkInTopic.subscribe(100)
        _    <- Stream.eval(Logger[F].info(s"Get msg in networkInTopic: ${msgFromNetwork}"))
        cache <- Stream.eval(cacheRef.get)
        mod     <- msgFromNetwork match {
          case RequestModifiersNetworkMessage(data) =>
            Stream.emits(data._2.flatMap(modId => cache.find(_._1 sameElements modId)).toList)
//            Stream.evals(data._2.flatMap(modId => cache.find(_._1 sameElements modId)).toList.traverse { case (modifierId, msg) =>
//              networkOutBuffer.enqueue1(Casting.castFromMessage2EncryNetMsgModifier(msg)) >> cacheRef.update(_ - modifierId)
//            })
          case _ =>
            Stream.emits(List.empty)
        }
        _ <- Stream.eval(networkOutBuffer.enqueue1(Casting.castFromMessage2EncryNetMsgModifier(mod._2)))
        _ <- Stream.eval(Logger[F].info(s"add to queue mod ${Algos.encode(mod._1)}"))
        _ <- Stream.eval(cacheRef.update(_ - mod._1))
      } yield ()

      val transactionTopicSubscriber = for {
        txMsg <- transactionTopic.subscribe(100)
        _     <- Stream.eval(Logger[F].info(s"Get msg from txExplorer: ${txMsg}"))
        _     <- txMsg match {
          case Messages.Init(_) => Stream.eval(Logger[F].info("In empty transactionTopicSubscriber"))
          case Messages.TransactionForNetwork(tx) =>
            Stream.eval(cacheRef.update(_ + (tx.id -> txMsg))) >>
              Stream.eval(
                networkOutBuffer.enqueue1(Casting.castFromMessage2EncryNetMsgInv(txMsg))
              )
        }
      } yield ()

      val sendingToNetworkTopic = networkOutTopic.publish(
        Stream.awakeEvery[F](0.5 seconds) zipRight networkOutBuffer.dequeue
          .evalTap(msg => Logger[F].info(s"try to request for msg: ${msg}"))
      )

      (sendingToNetworkTopic concurrently (networkTopicSubscriber concurrently transactionTopicSubscriber)).handleErrorWith { err =>
        Stream.eval(Logger[F].error(s"R&R service err ${err}")) >> Stream.empty
      }
      }
  }

  def apply[F[_]: Concurrent : Timer : Logger](transactionTopic: Topic[F, Message],
                                               networkInTopic: Topic[F, NetworkMessage],
                                               networkOutTopic: Topic[F, NetworkMessage]): F[RequestAndResponseProgram[F]] = for {
    networkOutBuffer <- Queue.bounded[F, NetworkMessage](100)
    cache <- Ref.of[F, Map[ModifierId, Message]](Map.empty)
  } yield new Live(networkOutBuffer, cache, transactionTopic, networkInTopic, networkOutTopic)
}

