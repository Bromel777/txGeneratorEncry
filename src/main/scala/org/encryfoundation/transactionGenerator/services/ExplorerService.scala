package org.encryfoundation.transactionGenerator.services

import java.util.concurrent.Executors

import cats.Applicative
import cats.effect.{ConcurrentEffect, IO, Resource, Sync}
import fs2.Stream
import io.chrisdavenport.log4cats.Logger
import org.encryfoundation.common.modifiers.state.box.EncryBaseBox
import org.encryfoundation.transactionGenerator.http.ExplorerRequests
import org.http4s.client.Client
import org.http4s.circe._
import org.http4s.client.blaze.BlazeClientBuilder

import scala.concurrent.ExecutionContext

trait ExplorerService[F[_]] {
  def getBoxesInRange(contractHash: String, from: Int, to: Int): F[List[EncryBaseBox]]
}

object ExplorerService {

  def apply[F[_]: Sync : ConcurrentEffect: Logger]: Resource[F, ExplorerService[F]] = {
    val ec = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(5))
    BlazeClientBuilder[F](ec).resource.map { client => new Live(client) }
  }

  final private class Live[F[_]: Sync : ConcurrentEffect: Logger](client: Client[F]) extends ExplorerService[F] {

    override def getBoxesInRange(contractHash: String,
                                 from: Int,
                                 to: Int): F[List[EncryBaseBox]] =
      client.expect[List[EncryBaseBox]](ExplorerRequests.boxesRequest(contractHash, from, to).uri)(jsonOf[F, List[EncryBaseBox]])
  }
}


