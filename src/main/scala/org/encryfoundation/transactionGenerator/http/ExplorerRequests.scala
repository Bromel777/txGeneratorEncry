package org.encryfoundation.transactionGenerator.http

import com.comcast.ip4s.{Ipv4Address, SocketAddress}
import org.http4s.{Method, Request, Uri}

object ExplorerRequests {

  def checkRequest[F[_]](explorerAddr: SocketAddress[Ipv4Address]) = Request[F](
    Method.GET,
    Uri.unsafeFromString(s"http://$explorerAddr/")
  )

  def boxesRequest[F[_]](contractHash: String,
                         from: Int,
                         to: Int,
                         explorerAddr: SocketAddress[Ipv4Address]) = Request[F](
    Method.GET,
    Uri.unsafeFromString(s"http://$explorerAddr/wallet/$contractHash/boxes/0/100")
  )
}
