package org.encryfoundation.transactionGenerator.services

import java.util.concurrent.Executors

import cats.effect.{ConcurrentEffect, Resource, Sync}
import io.chrisdavenport.log4cats.Logger
import org.encryfoundation.common.modifiers.state.box.EncryBaseBox
import org.encryfoundation.transactionGenerator.http.ExplorerRequests
import org.encryfoundation.transactionGenerator.settings.GeneratorSettings.ExplorerSettings
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder

import scala.concurrent.ExecutionContext

trait ExplorerService[F[_]] {
  def getBoxesInRange(contractHash: String, from: Int, to: Int): F[List[EncryBaseBox]]
}

object ExplorerService {

  private def fixedPool[F[_]](implicit F: Sync[F]): Resource[F, ExecutionContext] =
    Resource(F.delay {
      val executor = Executors.newFixedThreadPool(5)
      val ec = ExecutionContext.fromExecutor(executor)
      (ec, F.delay(executor.shutdown()))
    })

  def apply[F[_]: ConcurrentEffect: Logger](explorerSettings: ExplorerSettings): Resource[F, ExplorerService[F]] = for {
    ec              <- fixedPool
    client          <- BlazeClientBuilder[F](ec).resource
  } yield new Live(client, explorerSettings)

  final private class Live[F[_]: ConcurrentEffect: Logger](client: Client[F], explorerSettings: ExplorerSettings) extends ExplorerService[F] {

    override def getBoxesInRange(contractHash: String,
                                 from: Int,
                                 to: Int): F[List[EncryBaseBox]] =
      client.expect[List[EncryBaseBox]](
        ExplorerRequests.boxesRequest(contractHash, from, to, explorerSettings.address).uri
      )(jsonOf[F, List[EncryBaseBox]])
  }
}


