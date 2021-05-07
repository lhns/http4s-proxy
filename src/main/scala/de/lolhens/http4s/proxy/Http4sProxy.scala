package de.lolhens.http4s.proxy

import cats.Monad
import cats.effect.{BracketThrow, Resource}
import cats.syntax.apply._
import cats.syntax.functor._
import fs2.Stream
import org.http4s.headers.Host
import org.http4s.{Request, Response, Uri}

object Http4sProxy {
  implicit class RequestOps[F[_]](val request: Request[F]) extends AnyVal {
    def withDestination(destination: Uri): Request[F] =
      request
        .withUri(destination)
        .putHeaders(Host(destination.authority.map(_.host.value).getOrElse(""), destination.port))
  }

  implicit class ResponseCompanionOps(val self: Response.type) extends AnyVal {
    def liftResource[F[_] : BracketThrow](resource: Resource[F, Response[F]]): F[Response[F]] =
      resource.allocated.map {
        case (response, release) =>
          response.withBodyStream(
            Stream.resource(Resource.make(Monad[F].unit)(_ => release)) *>
              response.body
          )
      }
  }

  implicit class UriOps(val uri: Uri) extends AnyVal {
    def withSchemeAndAuthority(uri: Uri): Uri = this.uri.copy(
      scheme = uri.scheme,
      authority = uri.authority
    )
  }
}
