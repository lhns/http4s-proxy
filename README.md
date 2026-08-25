# http4s-proxy

[![Test Workflow](https://github.com/lhns/http4s-proxy/workflows/test/badge.svg)](https://github.com/lhns/http4s-proxy/actions?query=workflow%3Atest)
[![Release Notes](https://img.shields.io/github/release/lhns/http4s-proxy.svg?maxAge=3600)](https://github.com/lhns/http4s-proxy/releases/latest)
[![Maven Central](https://img.shields.io/maven-central/v/de.lhns/http4s-proxy_2.13)](https://search.maven.org/artifact/de.lhns/http4s-proxy_2.13)
[![Apache License 2.0](https://img.shields.io/github/license/lhns/http4s-proxy.svg?maxAge=3600)](https://www.apache.org/licenses/LICENSE-2.0)
[![Scala Steward badge](https://img.shields.io/badge/Scala_Steward-helping-blue.svg?style=flat&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAAQCAMAAAARSr4IAAAAVFBMVEUAAACHjojlOy5NWlrKzcYRKjGFjIbp293YycuLa3pYY2LSqql4f3pCUFTgSjNodYRmcXUsPD/NTTbjRS+2jomhgnzNc223cGvZS0HaSD0XLjbaSjElhIr+AAAAAXRSTlMAQObYZgAAAHlJREFUCNdNyosOwyAIhWHAQS1Vt7a77/3fcxxdmv0xwmckutAR1nkm4ggbyEcg/wWmlGLDAA3oL50xi6fk5ffZ3E2E3QfZDCcCN2YtbEWZt+Drc6u6rlqv7Uk0LdKqqr5rk2UCRXOk0vmQKGfc94nOJyQjouF9H/wCc9gECEYfONoAAAAASUVORK5CYII=)](https://scala-steward.org)

Utilities to create proxies in http4s.

### build.sbt

```sbt
// use this snippet for http4s 0.23
libraryDependencies += "de.lhns" %% "http4s-proxy" % "0.5.0"
```

Cross-built for Scala 2.13 and 3.3 (LTS), on both the JVM and Scala.js.

## ProxyApp

A replacement for `Client#toHttpApp` for use in a proxy.

`Client#toHttpApp` reaches its connection-release action only through the response body
stream, and its own scaladoc makes that the caller's problem: *"It is the responsibility of
callers of this service to run the response body to dispose of the underlying HTTP
connection."* A proxied response whose body is never run therefore leaks the upstream
exchange permanently. Behind Ember, which runs connections through `parJoin(maxConnections)`,
enough of those stop the accept loop and the server stops accepting while the backend is
perfectly healthy.

`ProxyApp` instead gives the exchange an owner: a supervised fiber holds it open inside
`Resource#use` for exactly as long as the body is in flight, so it is released on completion,
error, cancelation and abandonment alike.

```scala
import de.lhns.http4s.proxy.ProxyApp
import scala.concurrent.duration._

// Allocate once, at application scope.
val proxy: Resource[IO, HttpApp[IO]] =
  ProxyApp(client, headerTimeout = 10.seconds, bodyIdleTimeout = 30.seconds)
```

`headerTimeout` bounds how long the upstream may take to produce response headers. It is what
frees the connection slot when a backend accepts a connection and then goes silent.

`bodyIdleTimeout` bounds *lack of progress*, not total duration: a large body streaming to a
slow client is fine, a body that stalls is not. Never being read at all is the first special
case of no progress, which is why one setting covers both.

Reading a body whose exchange has already been reclaimed fails with
`ProxyApp.ReclaimedException` rather than returning a silently truncated one -- in a proxy,
crashing beats corrupting.

Closing the returned `Resource` cancels every exchange still in flight, so it must not be
allocated per request. If you already have a `Supervisor` of the right lifetime, use
`ProxyApp.withSupervisor` to make that ownership explicit.

## Request and Uri helpers

```scala
import de.lhns.http4s.proxy.Http4sProxy._

// Point a request at another server, setting Host from the new authority.
request.withDestination(uri"https://upstream:8443/some/path")

// Keep this uri's path and query, take scheme and authority from another.
request.uri.withSchemeAndAuthority(uri"https://upstream:8443")

// Build a Host header from an Authority.
Host.fromAuthority(authority)
```

## License

This project uses the Apache 2.0 License. See the file called LICENSE.
