package org.encryfoundation.transactionGenerator.services

import cats.Applicative
import cats.effect.{ConcurrentEffect, Sync}
import io.chrisdavenport.log4cats.Logger
import org.encryfoundation.common.modifiers.state.box.EncryBaseBox
import org.encryfoundation.transactionGenerator.http.ExplorerRequests
import org.http4s.client.Client
import org.http4s.circe._

trait ExplorerService[F[_]] {

  def getBoxesInRange(contractHash: String, from: Int, to: Int): F[List[EncryBaseBox]]
}

object ExplorerService {

  def apply[F[_]: Sync : ConcurrentEffect](client: Client[F], logger: Logger[F]): F[ExplorerService[F]] =
    Sync[F].delay(new Live(client, logger))

  final private class Live[F[_]: Sync : ConcurrentEffect](client: Client[F], logger: Logger[F]) extends ExplorerService[F] {

    override def getBoxesInRange(contractHash: String,
                                 from: Int,
                                 to: Int): F[List[EncryBaseBox]] =
      client.expect[List[EncryBaseBox]](ExplorerRequests.boxesRequest(contractHash, from, to).uri)(jsonOf[F, List[EncryBaseBox]])
  }
}


