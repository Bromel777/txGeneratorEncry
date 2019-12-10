package com.github.bromel777.utils

import cats.effect.IO
import org.encryfoundation.common.modifiers.history.Header
import org.encryfoundation.common.network.BasicMessagesRepo.{InvNetworkMessage, NetworkMessage}
import org.encryfoundation.common.utils.TaggedTypes.{ModifierId, ModifierTypeId}
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.Matchers
import scorex.utils.Random
import fs2.Stream
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.encryfoundation.common.utils.Algos

class SerializerSpec extends AnyPropSpec with Matchers {

  property("Serialization test") {
    val testMsg: List[InvNetworkMessage] = (0 to 100).map(_ => InvNetworkMessage((Header.modifierTypeId, Seq(ModifierId @@ Random.randomBytes())))).toList
    val toBytesStream = Stream.fromIterator[IO](testMsg.iterator)
    val bytes = toBytesStream.through(Serializer.toBytes)
    val bytesDeser = for {
      logger <- Stream.eval(Slf4jLogger.create[IO])
    } yield bytes.through(Serializer.fromBytes(logger))
    val res = bytesDeser.compile.toList.unsafeRunSync()
    testMsg.zip(res.map(_.asInstanceOf[InvNetworkMessage]))
      .forall { case (msg1, msg2) =>
        msg1.data._1 == msg2.data._1 && (msg1.data._2.zip(msg2.data._2).forall(tup => tup._1 sameElements tup._2))
      } shouldBe true
  }
}
