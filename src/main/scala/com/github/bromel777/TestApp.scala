package com.github.bromel777

import cats.Applicative
import cats.effect.concurrent.Ref
import cats.effect.{Blocker, Concurrent, ContextShift, ExitCode, IO, IOApp, Sync}
import fs2.{Chunk, Pipe, Pull, Stream}
import cats.implicits._
import com.comcast.ip4s.Port
import com.github.bromel777.network.Network
import fs2.io.tcp.SocketGroup
import io.chrisdavenport.log4cats.SelfAwareStructuredLogger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import scorex.utils.Random

import scala.collection.immutable

object TestApp extends IOApp {

  val sockets = for {
      blocker     <- Blocker[IO]
      socketGroup <- SocketGroup[IO](blocker)
    } yield socketGroup

  val program = for {
      logger <- Stream.eval(Slf4jLogger.create[IO])
      _ <- Network.startServer(Port(1234).get, sockets, logger)
    } yield ()

  override def run(args: List[String]): IO[ExitCode] = program.compile.drain.as(ExitCode.Success)
}

class First[F[_]: Concurrent](ref: Ref[F, Int]) {

  val a: F[Unit] = for {
    variable1 <- ref.get
    _ <- Applicative[F].pure(println(s"I am the first stream. Ref before update: ${variable1}"))
    _ <- ref.update(_ + 1)
    variable <- ref.get
    str <- Applicative[F].pure(println(s"I am the first stream. Variable is: ${variable}"))
  } yield str

  val b: F[Unit] = for {
    variable1 <- ref.get
    _ <- Applicative[F].pure(println(s"I am the second stream. Ref before update: ${variable1}"))
    _ <- ref.update(_ * 2)
    variable <- ref.get
    str <- Applicative[F].pure(println(s"I am the second stream. Variable is: ${variable}"))
  } yield str

  def rand = (0 to 10).map(_ => Random.randomBytes()).toList

  val bytes = rand.flatten.toArray

  val bytes2 = rand.flatten.toArray

  val chunks: Chunk[Byte] = Chunk.bytes(bytes)

  val chunks2: Chunk[Byte] = Chunk.bytes(bytes2.take(32))

  val byteStream = Stream.chunk[F, Byte](chunks) ++ Stream.chunk[F, Byte](chunks2)

  def deserPipe: Pipe[F, Byte, Array[Byte]] = {
    def pullDef(s: Stream[F, Byte], n: Long): Pull[F, Byte, Unit] =
      s.pull.uncons.flatMap {
        case Some((hd,tl)) =>
          hd.size match {
            case m if m > n => Pull.output(hd.take(1)) >> pullDef(tl, n - m)
            case m => Pull.output(hd.take(1)) >> Pull.done
          }
        case None => Pull.done
      }

    in => pullDef(in,2).stream.chunks.map(b => println("Size:" + b.size)) >> Stream.emit(Random.randomBytes())
  }

  val program = byteStream.covary.through(deserPipe).map(str => println(str.length))
}
