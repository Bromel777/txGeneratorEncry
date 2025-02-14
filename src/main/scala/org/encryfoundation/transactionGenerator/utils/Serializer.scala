package org.encryfoundation.transactionGenerator.utils

import cats.effect.Sync
import cats.implicits._
import com.google.common.primitives.Ints
import com.typesafe.scalalogging.StrictLogging
import fs2.{Chunk, Pipe, Pull, RaiseThrowable, Stream}
import io.chrisdavenport.log4cats.Logger
import org.encryfoundation.common.network.BasicMessagesRepo.{GeneralizedNetworkMessage, Handshake, NetworkMessage}
import org.encryfoundation.common.utils.Algos

object Serializer extends StrictLogging {

  def fromBytes[F[_]: Sync : RaiseThrowable : Logger]: Pipe[F, Byte, NetworkMessage] = {
    def deser(bytesStream: Stream[F, Byte],
              buffer: Chunk[Byte],
              msgSizeBuf: Option[Int],
              msgSizeChunkBuf: Chunk[Byte]): Pull[F, NetworkMessage, Unit] = {
        bytesStream.pull.uncons.flatMap {
          case Some((hd, tail)) =>
            msgSizeBuf match {
              case Some(msgSize) =>
                if ((buffer.size + hd.size) >= msgSize) {
                  Pull.eval(Logger[F].info(s"Chunk.concat(Seq(buffer, hd.take(msgSize - buffer.size))).toArray): " +
                    s"${Algos.encode(buffer.toArray[Byte] ++ hd.take(msgSize - buffer.size).toArray[Byte])}. Hd: ${hd.getClass}. ${
                      Chunk.concat(Seq(buffer, hd.take(msgSize - buffer.size))).size
                    }. MsgSize: ${msgSize}")) >>
                  Pull.output[F, NetworkMessage](
                    Chunk(GeneralizedNetworkMessage.fromProto(
                      buffer.toArray[Byte] ++ hd.take(msgSize - buffer.size).toArray[Byte]
                      ).get
                    )
                  ) >> deser(Stream.chunk(hd.drop(msgSize - buffer.size)) ++ tail, Chunk.empty, msgSizeBuf = None, Chunk.empty)
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
                  case _ if hd.size <= 4 =>
                    Pull.eval(Logger[F].info(s"case any. Head length: ${hd.size}")) >> deser(tail,
                      Chunk.empty,
                      None,
                      Chunk.concatBytes(Seq(msgSizeChunkBuf, hd))
                    )
                  case _ =>
                    Pull.eval(Logger[F].info(s"case any. Head length: ${hd.size}")) >> deser(Stream.chunk(hd.drop(4)) ++ tail,
                      Chunk.empty,
                      None,
                      Chunk.concatBytes(Seq(msgSizeChunkBuf, hd.take(4)))
                    )
                }
            }
          case None => Pull.eval(Logger[F].info("case none")) >> Pull.done
        }
    }
    is => deser(is, Chunk.empty, None, Chunk.empty).stream
  }

  def handshakeFromBytes[F[_]: Logger]: Pipe[F, Byte, NetworkMessage] = { is =>
    def concatPull(is: Stream[F, Byte], buffer: Chunk[Byte]): Pull[F, NetworkMessage, Unit] = {
      is.pull.uncons.flatMap {
        case Some((hd, tailStream)) => Pull.eval(Logger[F].info("handshakeFromBytes1")) >> concatPull(tailStream, Chunk.concat(List(buffer, hd)))
        case None => Pull.eval(Logger[F].info(s"handshakeFromBytes2. ${buffer}")) >> Pull.output(Chunk(GeneralizedNetworkMessage.fromProto(buffer.toArray).get)) >> Pull.done
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
