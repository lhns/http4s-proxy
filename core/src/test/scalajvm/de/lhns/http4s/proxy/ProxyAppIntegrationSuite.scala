package de.lhns.http4s.proxy

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all._
import com.comcast.ip4s._
import munit.FunSuite
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.jdkhttpclient.JdkHttpClient
import cats.data.Kleisli
import org.http4s.headers.`Content-Length`
import org.http4s.{HttpApp, Method, Request, Response, Status, Uri}

import scala.concurrent.duration._

/** Exercises ProxyApp against a real JdkHttpClient talking to a real Ember server, so that the
  * body-stream rewiring is validated against actual reactive-streams plumbing rather than a stub.
  */
class ProxyAppIntegrationSuite extends FunSuite {
  override val munitTimeout: FiniteDuration = 180.seconds

  private val small = "hello upstream"

  /** Deterministic binary payload: catches anything that round-trips text but mangles bytes. */
  private val binary: Array[Byte] = {
    val bytes = new Array[Byte](1024 * 1024)
    new scala.util.Random(42).nextBytes(bytes)
    bytes
  }
  private val large = "x" * (5 * 1024 * 1024)

  private val backendApp: HttpApp[IO] = HttpApp[IO] {
    case GET -> Root / "small" => Ok(small)
    case GET -> Root / "large" => Ok(large)
    case request @ POST -> Root / "echo" => Ok(request.body)
    case GET -> Root / "boom" => InternalServerError("upstream failed")
    case GET -> Root / "chunked" => Ok(fs2.Stream.emits(small.getBytes("UTF-8")).covary[IO])
    case GET -> Root / "hang" => IO.never
    case GET -> Root / "binary" => Ok(fs2.Stream.emits(binary).covary[IO])
    case GET -> Root / "stall" =>
      Ok(fs2.Stream.emits(binary.take(131072)).covary[IO] ++ fs2.Stream.exec(IO.never[Unit]))
    case GET -> Root / "die" =>
      Ok(
        fs2.Stream.emits(binary.take(4096)).covary[IO] ++
          fs2.Stream.raiseError[IO](new RuntimeException("upstream died mid-body"))
      )
    case GET -> Root / "empty" => NoContent()
    // RFC-legal and common from nginx/Tomcat: a 304 may carry the Content-Length the entity *would*
    // have had, with no body. A proxy that forwards that header naively makes clients wait forever.
    case GET -> Root / "notModified" =>
      IO.pure(Response[IO](Status.NotModified).putHeaders(`Content-Length`.unsafeFromLong(4096L)))
    case HEAD -> Root / "small" =>
      IO.pure(Response[IO](Status.Ok).putHeaders(`Content-Length`.unsafeFromLong(small.length.toLong)))
    // Same stall, but framed by a fixed Content-Length rather than chunked encoding: the response
    // promises 1MB and delivers 4KB, which is the classic way a proxy silently truncates.
    case GET -> Root / "stallFixed" =>
      IO.pure(
        Response[IO](Status.Ok)
          .withBodyStream(fs2.Stream.emits(binary.take(131072)).covary[IO] ++ fs2.Stream.exec(IO.never[Unit]))
          .putHeaders(`Content-Length`.unsafeFromLong(binary.length.toLong))
      )
    case _ => NotFound()
  }

  private val backend: Resource[IO, Uri] =
    EmberServerBuilder
      .default[IO]
      .withHost(host"127.0.0.1")
      .withPort(port"0")
      .withHttpApp(backendApp)
      .withShutdownTimeout(1.second)
      .build
      .map(server => Uri.unsafeFromString(s"http://127.0.0.1:${server.address.getPort}"))

  /** Wraps the real client so that release of the real upstream exchange is observable. */
  private def counting(underlying: Client[IO], releases: Ref[IO, Int]): Client[IO] =
    Client[IO](request => underlying.run(request).onFinalize(releases.update(_ + 1)))

  private def fixture[A](bodyIdleTimeout: FiniteDuration)(f: (HttpApp[IO], Uri, Ref[IO, Int]) => IO[A]): A =
    (for {
      uri <- backend
      raw <- JdkHttpClient.simple[IO]
      releases <- Resource.eval(Ref[IO].of(0))
      app <- ProxyApp(counting(raw, releases), 10.seconds, bodyIdleTimeout)
    } yield (app, uri, releases)).use(f.tupled).unsafeRunSync()

  test("small body round-trips through a real JdkHttpClient and releases once") {
    val (body, releases) = fixture(20.seconds) { (app, uri, releases) =>
      for {
        response <- app(Request[IO](Method.GET, uri / "small"))
        body <- response.bodyText.compile.string
        _ <- IO.sleep(500.millis)
        n <- releases.get
      } yield (body, n)
    }
    assertEquals(body, small)
    assertEquals(releases, 1)
  }

  test("5MB body survives the stream rewiring byte-exactly and releases once") {
    val (length, matches, releases) = fixture(20.seconds) { (app, uri, releases) =>
      for {
        response <- app(Request[IO](Method.GET, uri / "large"))
        body <- response.bodyText.compile.string
        _ <- IO.sleep(500.millis)
        n <- releases.get
      } yield (body.length, body == large, n)
    }
    assertEquals(length, large.length)
    assert(matches, "large body was corrupted in transit")
    assertEquals(releases, 1)
  }

  test("real exchange whose body is never consumed is released after the grace period") {
    val (duringGrace, afterGrace) = fixture(2.seconds) { (app, uri, releases) =>
      for {
        _ <- app(Request[IO](Method.GET, uri / "small")) // response dropped, body never compiled
        duringGrace <- IO.sleep(500.millis) *> releases.get
        afterGrace <- IO.sleep(3.seconds) *> releases.get
      } yield (duringGrace, afterGrace)
    }
    assertEquals(duringGrace, 0, "must not release while the body is still claimable")
    assertEquals(afterGrace, 1, "the abandoned upstream exchange must be reclaimed")
  }

  test("300 sequential real requests all succeed and release every connection") {
    val (bad, releases) = fixture(20.seconds) { (app, uri, releases) =>
      for {
        bodies <- List.range(0, 300).traverse { _ =>
          app(Request[IO](Method.GET, uri / "small")).flatMap(_.bodyText.compile.string)
        }
        _ <- IO.sleep(500.millis)
        n <- releases.get
      } yield (bodies.count(_ != small), n)
    }
    assertEquals(bad, 0)
    assertEquals(releases, 300)
  }

  /** The real production shape: browser -> Ember gateway -> ProxyApp -> JdkHttpClient -> backend. */
  private def endToEnd[A](
                          bodyIdleTimeout: FiniteDuration,
                          headerTimeout: FiniteDuration = 10.seconds,
                          maxConnections: Int = 1024,
                          gatewayIdleTimeout: FiniteDuration = 60.seconds
                        )(f: (Client[IO], Uri, Ref[IO, Int]) => IO[A]): A =
    (for {
      backendUri <- backend
      upstream <- JdkHttpClient.simple[IO]
      releases <- Resource.eval(Ref[IO].of(0))
      proxyApp <- ProxyApp(counting(upstream, releases), headerTimeout, bodyIdleTimeout)
      gatewayApp = Kleisli { (request: Request[IO]) =>
        proxyApp(request.withUri(backendUri.withPath(request.uri.path)))
      }: HttpApp[IO]
      gateway <- EmberServerBuilder
        .default[IO]
        .withHost(host"127.0.0.1")
        .withPort(port"0")
        .withHttpApp(gatewayApp)
        .withMaxConnections(maxConnections)
        .withIdleTimeout(gatewayIdleTimeout)
        .withShutdownTimeout(1.second)
        .build
      browser <- JdkHttpClient.simple[IO]
    } yield (browser, Uri.unsafeFromString(s"http://127.0.0.1:${gateway.address.getPort}"), releases))
      .use(f.tupled)
      .unsafeRunSync()

  test("end to end through a real Ember gateway: small and 5MB bodies are byte-exact") {
    val (small0, large0, releases) = endToEnd(5.seconds) { (browser, gateway, releases) =>
      for {
        a <- browser.expect[String](gateway / "small")
        b <- browser.expect[String](gateway / "large")
        _ <- IO.sleep(500.millis)
        n <- releases.get
      } yield (a, b, n)
    }
    assertEquals(small0, small)
    assertEquals(large0.length, large.length)
    assert(large0 == large, "5MB body was corrupted end to end")
    assertEquals(releases, 2)
  }

  test("end to end: a request body is forwarded upstream") {
    val echoed = endToEnd(5.seconds) { (browser, gateway, _) =>
      browser.expect[String](Request[IO](Method.POST, gateway / "echo").withEntity("payload up"))
    }
    assertEquals(echoed, "payload up")
  }

  test("end to end: an upstream error status passes through unchanged") {
    val status = endToEnd(5.seconds) { (browser, gateway, _) =>
      browser.status(Request[IO](Method.GET, gateway / "boom"))
    }
    assertEquals(status, Status.InternalServerError)
  }

  test("end to end: 50 concurrent requests all succeed and release every connection") {
    val (bad, releases) = endToEnd(5.seconds) { (browser, gateway, releases) =>
      for {
        bodies <- List.range(0, 50).parTraverse(_ => browser.expect[String](gateway / "small"))
        _ <- IO.sleep(1.second)
        n <- releases.get
      } yield (bodies.count(_ != small), n)
    }
    assertEquals(bad, 0)
    assertEquals(releases, 50)
  }

  test("end to end: a chunked upstream response (no Content-Length) round-trips") {
    val body = endToEnd(5.seconds) { (browser, gateway, _) =>
      browser.expect[String](gateway / "chunked")
    }
    assertEquals(body, small)
  }

  test("a hanging backend does not permanently consume gateway connection slots") {
    // The reported production symptom: Ember runs connections through parJoin(maxConnections), so
    // a handler fiber that never finishes holds its slot forever, the accept loop stops, and every
    // new client times out at connect while the backend itself is healthy.
    //
    // Asserting on the hanging requests rather than racing a healthy one against them is
    // deliberate. A healthy request has to win a connection slot back from clients that are
    // holding theirs open with keep-alive, which measures Ember's idle timeout rather than
    // anything about ProxyApp. What matters here is that the hung handlers terminate at all:
    // with far more of them than there are slots, the later ones can only be accepted once the
    // earlier ones let go, so this does not terminate if a hung fiber keeps its slot.
    val outcomes = endToEnd(
      5.seconds,
      headerTimeout = 1.second,
      maxConnections = 8,
      // Ember holds a connection's slot open for keep-alive after responding, for as long as its
      // idle timeout. At the 60s default, 40 concurrent connections against 8 slots can never all
      // be served no matter how quickly the handlers finish -- which would make this a test of
      // keep-alive rather than of ProxyApp.
      gatewayIdleTimeout = 1.second
    ) {
      (browser, gateway, _) =>
        List
          .range(0, 40)
          .parTraverse(_ => browser.expect[String](gateway / "hang").attempt)
          .timeout(60.seconds)
    }
    assertEquals(outcomes.length, 40)
    assert(outcomes.forall(_.isLeft), "a request to a hanging upstream must fail, not hang")
  }

  private def bytesOf(client: Client[IO], uri: Uri): IO[Array[Byte]] =
    client.run(Request[IO](Method.GET, uri)).use(_.body.compile.to(Array))

  test("binary payload is byte-exact end to end") {
    val received = endToEnd(20.seconds) { (browser, gateway, _) =>
      bytesOf(browser, gateway / "binary")
    }
    assertEquals(received.length, binary.length)
    assert(java.util.Arrays.equals(received, binary), "binary payload was corrupted in transit")
  }

  test("20 concurrent 1MB binary payloads are each byte-exact (no cross-talk between exchanges)") {
    val corrupted = endToEnd(20.seconds) { (browser, gateway, _) =>
      List.range(0, 20).parTraverse(_ => bytesOf(browser, gateway / "binary"))
        .map(_.count(received => !java.util.Arrays.equals(received, binary)))
    }
    assertEquals(corrupted, 0, "concurrent exchanges corrupted each other's bodies")
  }

  /** Reads headers and body separately, so a body failure cannot be confused with a header failure. */
  private def statusThenBody(client: Client[IO], uri: Uri): IO[(Status, Either[Throwable, Int])] =
    client.run(Request[IO](Method.GET, uri)).use { response =>
      response.body.compile.to(Array).attempt.map(body => (response.status, body.map(_.length)))
    }

  test("a chunked body that stalls mid-stream fails the downstream client rather than truncating") {
    val (status, body) = endToEnd(2.seconds)((browser, gateway, _) => statusThenBody(browser, gateway / "stall"))
    assertEquals(status, Status.Ok, "headers must have succeeded, or this proves nothing about the body")
    assert(body.isLeft, s"downstream saw a SUCCESSFUL ${body}-byte response for a stalled 1MB body")
  }

  test("a Content-Length framed body that stalls fails the downstream client rather than truncating") {
    val (status, body) = endToEnd(2.seconds)((browser, gateway, _) => statusThenBody(browser, gateway / "stallFixed"))
    assertEquals(status, Status.Ok, "headers must have succeeded, or this proves nothing about the body")
    assert(body.isLeft, s"downstream accepted ${body} bytes of a response that promised ${binary.length}")
  }

  test("an upstream that dies mid-body fails the downstream client rather than truncating") {
    val (status, body) = endToEnd(20.seconds)((browser, gateway, _) => statusThenBody(browser, gateway / "die"))
    assertEquals(status, Status.Ok, "headers must have succeeded, or this proves nothing about the body")
    assert(body.isLeft, s"downstream saw a SUCCESSFUL ${body}-byte response for a body that errored")
  }

  test("an empty (204) upstream response passes through and releases") {
    val (status, releases) = endToEnd(20.seconds) { (browser, gateway, releases) =>
      for {
        status <- browser.status(Request[IO](Method.GET, gateway / "empty"))
        _ <- IO.sleep(500.millis)
        n <- releases.get
      } yield (status, n)
    }
    assertEquals(status, Status.NoContent)
    assertEquals(releases, 1)
  }

  test("a 304 carrying a Content-Length does not hang the downstream client") {
    val (status, body) = endToEnd(20.seconds) { (browser, gateway, _) =>
      browser.run(Request[IO](Method.GET, gateway / "notModified")).use { response =>
        response.body.compile.to(Array).attempt.map(b => (response.status, b.map(_.length)))
      }.timeout(30.seconds)
    }
    assertEquals(status, Status.NotModified)
    assertEquals(body, Right(0), "a 304 must yield an empty body, not a stalled read")
  }

  test("a HEAD response with a Content-Length does not hang the downstream client") {
    val (status, body) = endToEnd(20.seconds) { (browser, gateway, _) =>
      browser.run(Request[IO](Method.HEAD, gateway / "small")).use { response =>
        response.body.compile.to(Array).attempt.map(b => (response.status, b.map(_.length)))
      }.timeout(30.seconds)
    }
    assertEquals(status, Status.Ok)
    assertEquals(body, Right(0), "a HEAD response must yield an empty body, not a stalled read")
  }
}
