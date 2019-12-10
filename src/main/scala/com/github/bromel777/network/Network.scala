package com.github.bromel777.network

import java.net.InetSocketAddress

import cats.Applicative
import cats.implicits._
import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Sync}
import io.chrisdavenport.log4cats.Logger
import com.comcast.ip4s._
import com.github.bromel777.network.Network.connectToPeers
import fs2.io.tcp.{Socket, SocketGroup}
import fs2.{Chunk, Stream}
import org.encryfoundation.common.network.BasicMessagesRepo.{GeneralizedNetworkMessage, Handshake}
import org.encryfoundation.common.utils.Algos

object Network {

  val dummyHandshake = Handshake(protocolToBytes("0.9.3"),
    "test node",
    Some(SocketAddress(ipv4"192.168.30.104", Port(1234).get).toInetSocketAddress),
    1575975624532L)

  println(Algos.encode(GeneralizedNetworkMessage.toProto(dummyHandshake).toByteArray))

  val peers: List[SocketAddress[Ipv4Address]] = List(SocketAddress(ipv4"0.0.0.0", Port(9040).get))

  def protocolToBytes(protocol: String) = protocol.split("\\.").map(elem => elem.toByte)

  def connectTo[F[_]: Concurrent : ContextShift](addr: SocketAddress[Ipv4Address],
                                                 socketGroup: SocketGroup,
                                                 logger: Logger[F]): Resource[F, Socket[F]] =
    socketGroup.client(addr.toInetSocketAddress)


  def startServer[F[_] : Concurrent : ContextShift](port: Port,
                                                    socketGroupF: Resource[F, SocketGroup],
                                                    logger: Logger[F]) = {
    val stream1 = for {
      socketGroup <- Stream.resource(socketGroupF)
      _           <- Stream.eval(logger.info(s"Start server at port ${port}"))
      socketRes   <- socketGroup.server(new InetSocketAddress(port.value))
      socket      <- Stream.resource(socketRes)
      handler     <- Stream.eval(ConnectionHandler(socket, logger))
      handlers    <- processIncoming(handler, logger)
    } yield handlers

    val stream2 = for {
      socketGroup <- Stream.resource(socketGroupF)
      _ <- connectToPeers(socketGroupF, logger)
    } yield ()

    stream1 concurrently stream2
  }

  def connectToPeers[F[_]: Sync : Concurrent : ContextShift](socketGroupF: Resource[F, SocketGroup], logger: Logger[F]) = for {
    peer        <- Stream.fromIterator(peers.toIterator)
    _           <- Stream.eval(logger.info(s"1"))
    socketGroup <- Stream.resource(socketGroupF)
    _           <- Stream.eval(logger.info(s"2"))
    socket      <- Stream.resource(socketGroup.client(peer.toInetSocketAddress))
    _           <- Stream.eval(logger.info(s"3"))
    handler     <- Stream.eval(ConnectionHandler(socket, logger))
    _           <- handle(handler, logger)
  } yield ()

  def processIncoming[F[_] : Concurrent](handler: ConnectionHandler[F], logger: Logger[F]) = for {
    _   <- Stream.eval(logger.info(s"test msg"))
    msg <- handler.read()
    _   <- Stream.eval(logger.info(s"Get msg: ${msg}"))
  } yield ()

  def handle[F[_]: Concurrent](handler: ConnectionHandler[F], logger: Logger[F]) = for {
    _ <- Stream.eval(handler.write(dummyHandshake))
    _ <- processIncoming(handler, logger)
  } yield ()
}
