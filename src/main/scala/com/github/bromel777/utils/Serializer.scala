package com.github.bromel777.utils

import cats.effect.Sync
import com.google.common.primitives.{Bytes, Ints}
import fs2.{Chunk, Pipe, Pull, Stream}
import org.encryfoundation.common.network.BasicMessagesRepo.{GeneralizedNetworkMessage, Handshake, NetworkMessage}
import cats.implicits._
import com.typesafe.scalalogging.StrictLogging
import fs2.Stream
import io.chrisdavenport.log4cats.Logger

object Serializer extends StrictLogging {

  def fromBytes[F[_]: Sync](logger: Logger[F]): Pipe[F, Byte, NetworkMessage] = {
    def deser(bytesStream: Stream[F, Byte],
              buffer: Chunk[Byte],
              msgSizeBuf: Option[Int],
              msgSizeChunkBuf: Chunk[Byte]): Pull[F, NetworkMessage, Unit] = {
        bytesStream.pull.uncons.flatMap {
          case Some((hd, tail)) =>
            msgSizeBuf match {
              case Some(msgSize) =>
                if ((buffer.size + hd.size) >= msgSize) {
                  Pull.output(
                    Chunk(GeneralizedNetworkMessage.fromProto(Chunk.concat(Seq(buffer, hd.take(msgSize - buffer.size))).toArray).get)
                  ) >> deser(tail, hd.drop(msgSize - buffer.size), msgSizeBuf = None, Chunk.empty)
                }
                else deser(tail, Chunk.concatBytes(Seq(buffer, hd)), msgSizeBuf, msgSizeChunkBuf)
              case None =>
                 msgSizeChunkBuf.size match {
                  case acceptSize if acceptSize >= 4 =>
                    deser(
                      Stream.chunk(hd) ++ tail,
                      Chunk.concatBytes(Seq(buffer, msgSizeChunkBuf.drop(4))),
                      Ints.fromByteArray(msgSizeChunkBuf.take(4).toArray).some,
                      Chunk.empty
                    )
                  case _ =>
                    Pull.eval(logger.info("case any")) >> deser(Stream.chunk(hd.drop(4)) ++ tail,
                      Chunk.empty,
                      None,
                      Chunk.concatBytes(Seq(msgSizeChunkBuf, hd.take(4)))
                    )
                }
            }
          case None => Pull.done
        }
    }
    is => deser(is, Chunk.empty, None, Chunk.empty).stream
  }

  def handshakeFromBytes[F[_]](logger: Logger[F]): Pipe[F, Byte, NetworkMessage] = { is =>
    def concatPull(is: Stream[F, Byte], buffer: Chunk[Byte]): Pull[F, NetworkMessage, Unit] = {
      is.pull.uncons.flatMap {
        case Some((hd, tailStream)) => Pull.eval(logger.info("handshakeFromBytes1")) >> concatPull(tailStream, Chunk.concat(List(buffer, hd)))
        case None => Pull.eval(logger.info(s"handshakeFromBytes2. ${buffer}")) >> Pull.output(Chunk(GeneralizedNetworkMessage.fromProto(buffer.toArray).get)) >> Pull.done
      }
    }
    concatPull(is, Chunk.empty).stream
  }

  def toBytes[F[_]]: Pipe[F, NetworkMessage, Byte] = {
    is => is.mapChunks(_.flatMap{
      case handshake: Handshake =>
        val msgBytes = GeneralizedNetworkMessage.toProto(handshake).toByteArray
        Chunk.bytes(msgBytes)
      case anyMsg =>
        val msgBytes = GeneralizedNetworkMessage.toProto(anyMsg).toByteArray
        Chunk.bytes(Ints.toByteArray(msgBytes.length) ++ msgBytes)
      }
    )
  }

}
