package de.lhns.http4s.proxy

import de.lhns.http4s.proxy.Http4sProxy._
import munit.FunSuite
import org.http4s.headers.Host
import org.http4s.{Request, Uri}

class Http4sProxySuite extends FunSuite {
  test("withDestination rewrites the uri and sets Host from its authority") {
    val request = Request[Option](uri = Uri.unsafeFromString("http://original/path?a=b"))
    val proxied = request.withDestination(Uri.unsafeFromString("https://upstream:8443/path?a=b"))

    assertEquals(proxied.uri.renderString, "https://upstream:8443/path?a=b")
    assertEquals(proxied.headers.get[Host], Some(Host("upstream", Some(8443))))
  }

  test("withSchemeAndAuthority keeps the path and query of the receiver") {
    val rewritten = Uri
      .unsafeFromString("http://original/path?a=b")
      .withSchemeAndAuthority(Uri.unsafeFromString("https://upstream:8443/ignored"))

    assertEquals(rewritten.renderString, "https://upstream:8443/path?a=b")
  }
}
