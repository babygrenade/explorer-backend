package org.ergoplatform.explorer.http.api.v1.routes

import cats.effect.{Concurrent, ContextShift, Timer}
import cats.syntax.semigroupk._
import org.ergoplatform.explorer.http.api.ApiErr
import org.ergoplatform.explorer.http.api.algebra.AdaptThrowable.AdaptThrowableEitherT
import org.ergoplatform.explorer.http.api.syntax.adaptThrowable._
import org.ergoplatform.explorer.http.api.syntax.routes._
import org.ergoplatform.explorer.http.api.v1.defs.EpochsEndpointDefs
import org.ergoplatform.explorer.http.api.v1.services.Epochs
import org.http4s.HttpRoutes
import sttp.tapir.server.http4s._

final class EpochsRoutes[
  F[_]: Concurrent: ContextShift: Timer: AdaptThrowableEitherT[*[_], ApiErr]
](epochs: Epochs[F])(implicit opts: Http4sServerOptions[F]) {

  val defs = new EpochsEndpointDefs[F]

  val routes: HttpRoutes[F] = getEpochInfoByHeightR <+> getEpochInfoByIdR

  private def getEpochInfoByHeightR: HttpRoutes[F] = ???
//    defs.getEpochInfoByHeightDef.toRoutes { height =>
//      epochs.getEpochParamsByHeight(height)
//    }

  private def getEpochInfoByIdR: HttpRoutes[F] = ???

}
