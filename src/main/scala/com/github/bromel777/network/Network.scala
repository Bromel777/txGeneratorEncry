package com.github.bromel777.network

import java.net.InetSocketAddress
import cats.implicits._
import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Sync}
import io.chrisdavenport.log4cats.Logger
import com.comcast.ip4s._
import fs2.io.tcp.{Socket, SocketGroup}
import fs2.{Chunk, Stream}

object Network {

  def startServer[F[_] : Concurrent : ContextShift](port: Port,
                                                    socketGroupF: Resource[F, SocketGroup],
                                                    logger: Logger[F]) = {
    for {
      socketGroup <- Stream.resource(socketGroupF)
      _           <- Stream.eval(logger.info(s"Start server at port ${port}"))
      sockets     <- socketGroup.server(new InetSocketAddress(port.value))
      _           <- handle(sockets, logger)
    } yield sockets
  }

  def handle[F[_] : Concurrent](socketRes: Resource[F, Socket[F]], logger: Logger[F]) = for {
    res <- Stream.resource(socketRes)
    _   <- Stream.eval_(logger.info(s"Send msg!"))
    _   <- Stream.eval_(res.write(Chunk.bytes("hello!".getBytes), None))
  } yield ()
}
