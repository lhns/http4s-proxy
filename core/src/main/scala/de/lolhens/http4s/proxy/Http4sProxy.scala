package de.lolhens.http4s.proxy

import org.http4s.Uri.Authority
import org.http4s.headers.Host
import org.http4s.{Request, Uri}

object Http4sProxy {
  implicit class HostCompanionOps(val self: Host.type) extends AnyVal {
    def fromAuthority(authority: Authority): Host =
      Host(authority.host.value, authority.port)
  }

  implicit class RequestOps[F[_]](val request: Request[F]) extends AnyVal {
    def withDestination(destination: Uri): Request[F] =
      request
        .withUri(destination)
        .putHeaders(destination.authority.fold(Host(""))(Host.fromAuthority))
  }

  implicit class UriOps(val uri: Uri) extends AnyVal {
    def withSchemeAndAuthority(uri: Uri): Uri = this.uri.copy(
      scheme = uri.scheme,
      authority = uri.authority
    )
  }
}
