package org.encryfoundation.transactionGenerator.services

import cats.Applicative
import cats.effect.{Concurrent, Timer}
import cats.effect.concurrent.Ref
import cats.implicits._
import fs2.Stream
import fs2.concurrent.{Queue, Topic}
import org.encryfoundation.common.network.BasicMessagesRepo.{NetworkMessage, RequestModifiersNetworkMessage}
import org.encryfoundation.common.utils.TaggedTypes.ModifierId
import org.encryfoundation.transactionGenerator.services.TransactionService.{Message, Messages}
import org.encryfoundation.transactionGenerator.utils.Casting
import io.chrisdavenport.log4cats.Logger
import org.encryfoundation.common.utils.Algos

import scala.concurrent.duration._

trait RequestAndResponseService[F[_]] {

  def start: Stream[F, Unit]
}

object RequestAndResponseService {

  private class Live[F[_]: Concurrent : Timer](transactionTopic: Topic[F, Message],
                                               networkInTopic: Topic[F, NetworkMessage],
                                               networkOutTopic: Topic[F, NetworkMessage],
                                               networkOutBuffer: Queue[F, NetworkMessage],
                                               cacheRef: Ref[F, Map[ModifierId, Message]],
                                               logger: Logger[F]) extends RequestAndResponseService[F] {

    override val start: Stream[F, Unit] = {
      val networkTopicSubscriber = for {
        msgFromNetwork <- networkInTopic.subscribe(100)
        _    <- Stream.eval(logger.info(s"Get msg in networkInTopic: ${msgFromNetwork}"))
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
        _ <- Stream.eval(logger.info(s"add to queue mod ${Algos.encode(mod._1)}"))
        _ <- Stream.eval(cacheRef.update(_ - mod._1))
      } yield ()

      val transactionTopicSubscriber = for {
        txMsg <- transactionTopic.subscribe(100)
        _     <- Stream.eval(logger.info(s"Get msg from txExplorer: ${txMsg}"))
        _     <- txMsg match {
          case Messages.Init(_) => Stream.eval(logger.info("In empty transactionTopicSubscriber"))
          case Messages.TransactionForNetwork(tx) =>
            Stream.eval(cacheRef.update(_ + (tx.id -> txMsg))) >>
              Stream.eval(
                networkOutBuffer.enqueue1(Casting.castFromMessage2EncryNetMsgInv(txMsg))
              )
        }
      } yield ()

      val sendingToNetworkTopic = networkOutTopic.publish(
        Stream.awakeEvery[F](0.5 seconds) zipRight networkOutBuffer.dequeue
          .evalTap(msg => logger.info(s"try to request for msg: ${msg}"))
      )

      (sendingToNetworkTopic concurrently (networkTopicSubscriber concurrently transactionTopicSubscriber)).handleErrorWith { err =>
        Stream.eval(logger.error(s"R&R service err ${err}")) >> Stream.empty
      }
      }
  }

  def apply[F[_]: Concurrent : Timer](transactionTopic: Topic[F, Message],
                                      networkInTopic: Topic[F, NetworkMessage],
                                      networkOutTopic: Topic[F, NetworkMessage],
                                      logger: Logger[F]): F[RequestAndResponseService[F]] = for {
    networkOutBuffer <- Queue.bounded[F, NetworkMessage](100)
    cache <- Ref.of[F, Map[ModifierId, Message]](Map.empty)
  } yield new Live(transactionTopic, networkInTopic, networkOutTopic, networkOutBuffer, cache, logger)
}

