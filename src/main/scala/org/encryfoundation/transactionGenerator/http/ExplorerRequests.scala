package org.encryfoundation.transactionGenerator.http

import cats.effect.IO
import com.comcast.ip4s.{Ipv4Address, SocketAddress}
import org.encryfoundation.transactionGenerator.TestApp.contractHash
import org.encryfoundation.prismlang.compiler.CompiledContract.ContractHash
import org.http4s.{Method, Request, Uri}

object ExplorerRequests {

  def boxesRequest[F[_]](contractHash: String,
                         from: Int,
                         to: Int,
                         explorerAddr: SocketAddress[Ipv4Address]) = Request[F](
    Method.GET,
    Uri.unsafeFromString(s"http://$explorerAddr/wallet/$contractHash/boxes/0/100")
  )
}
