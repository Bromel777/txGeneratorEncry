package org.encryfoundation.transactionGenerator.http

import cats.effect.IO
import org.encryfoundation.transactionGenerator.TestApp.contractHash
import org.encryfoundation.prismlang.compiler.CompiledContract.ContractHash
import org.http4s.{Method, Request, Uri}

object ExplorerRequests {

  def boxesRequest[F[_]](contractHash: String,
                         from: Int,
                         to: Int) = Request[F](
    Method.GET,
    Uri.unsafeFromString(s"http://172.16.10.58:9000/wallet/$contractHash/boxes/0/100")
  )
}
