package com.github.bromel777.network

import fs2.io.tcp._
import fs2.Stream
import org.encryfoundation.common.network.BasicMessagesRepo.NetworkMessage

class ConnectionHandler[F[_]](socket: Socket[F]) {

  def read(): Stream[F, NetworkMessage] = ???

}
