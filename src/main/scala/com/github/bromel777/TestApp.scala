package com.github.bromel777

import java.util.concurrent.Executors
import cats.effect.concurrent.Ref
import cats.effect.{Blocker, Concurrent, ExitCode, IO, IOApp}
import cats.implicits._
import com.comcast.ip4s.Port
import com.github.bromel777.network.Network
import fs2.Stream
import fs2.io.tcp.SocketGroup
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import jawnfs2._
import org.http4s._
import org.http4s.client.blaze._

import scala.concurrent.ExecutionContext

object TestApp extends IOApp {

  implicit val f = io.circe.jawn.CirceSupportParser.facade

  val req = Request[IO](Method.GET, Uri.uri("http://88.198.234.20:9051/info"))

  def makeJsonRequest(logger: Logger[IO], ex: ExecutionContext) = for {
    client    <- BlazeClientBuilder[IO](ex).stream
    response  <- client.stream(req).flatMap(_.body.chunks.parseJsonStream)
    _         <- Stream.eval(logger.info(s"resp: ${response.spaces2}"))
  } yield response

  val sockets = for {
      blocker     <- Blocker[IO]
      socketGroup <- SocketGroup[IO](blocker)
    } yield socketGroup

  val program = for {
      logger <- Stream.eval(Slf4jLogger.create[IO])
      _ <- Network.startServer(Port(1000).get, sockets, logger)
    } yield ()

  val reqProg =
    for {
      context <- Stream.emit(ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(5)))
      logger  <- Stream.eval(Slf4jLogger.create[IO])
      _       <- makeJsonRequest(logger, context)
    } yield ()


  override def run(args: List[String]): IO[ExitCode] =
    (program concurrently reqProg).compile.drain.as(ExitCode.Success)
}

class First[F[_]: Concurrent](ref: Ref[F, Int]) {

}
