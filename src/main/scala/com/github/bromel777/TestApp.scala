package com.github.bromel777

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

object TestApp extends IOApp {

  implicit val f = io.circe.jawn.CirceSupportParser.facade

  val req = Request[IO](Method.GET, Uri.uri("http://88.198.234.19:9051/info"))

  def makeJsonRequest(logger: Logger[IO], bl: Blocker) = for {
    client    <- BlazeClientBuilder[IO](bl.blockingContext).stream
    response  <- client.stream(req).flatMap(_.body.chunks.parseJsonStream)
//    _         <- Stream.eval(logger.info(s"resp: ${response.spaces2}"))
  } yield response

//  val sockets = for {
//      blocker     <- Blocker[IO]
//      socketGroup <- SocketGroup[IO](blocker)
//    } yield socketGroup
//
//  val program = for {
//      logger <- Stream.eval(Slf4jLogger.create[IO])
//      _ <- Network.startServer(Port(1234).get, sockets, logger)
//    } yield ()

  val reqProg =
    for {
      blocker <- Stream.resource(Blocker[IO])
      logger  <- Stream.eval(Slf4jLogger.create[IO])
      _       <- makeJsonRequest(logger, blocker)
    } yield ()


  override def run(args: List[String]): IO[ExitCode] =
    reqProg.compile.drain.as(ExitCode.Success)
}

class First[F[_]: Concurrent](ref: Ref[F, Int]) {

}
