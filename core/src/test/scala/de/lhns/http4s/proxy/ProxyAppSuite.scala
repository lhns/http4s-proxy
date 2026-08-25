package de.lhns.http4s.proxy

import cats.effect.testkit.TestControl
import cats.syntax.all._
import cats.effect.{IO, Ref, Resource}
import fs2.Stream
import munit.CatsEffectSuite
import org.http4s.client.Client
import org.http4s.{Method, Request, Response, Status, Uri}

import java.util.concurrent.TimeoutException
import scala.concurrent.duration._

class ProxyAppSuite extends CatsEffectSuite {

  private val headerTimeout = 5.seconds
  private val bodyIdleTimeout = 10.seconds

  private val payload = "hello upstream"
  private def payloadStream: Stream[IO, Byte] = Stream.emits(payload.getBytes("UTF-8")).covary[IO]

  private val req = Request[IO](Method.GET, uri = Uri.unsafeFromString("http://upstream/x"))

  /** A client whose release is observable and whose timing is fully controlled. */
  private def probeClient(
                           releases: Ref[IO, Int],
                           acquireDelay: FiniteDuration = Duration.Zero,
                           body: Stream[IO, Byte] = payloadStream,
                           acquireFailure: Option[Throwable] = None
                         ): Client[IO] =
    Client[IO] { _ =>
      Resource.make(
        IO.sleep(acquireDelay) *>
          acquireFailure.fold(IO.pure(Response[IO](Status.Ok).withBodyStream(body)))(IO.raiseError)
      )(_ => releases.update(_ + 1))
    }

  /** Runs under virtual time; fails the test if the program cannot make progress.
    *
    * Returns IO rather than unsafeRunSync-ing: the latter is JVM only, since Scala.js cannot
    * block a thread. TestControl itself is a pure simulation and runs identically on both.
    */
  private def run[A](io: IO[A]): IO[A] = TestControl.executeEmbed(io)

  private def withApp[A](client: Client[IO])(f: org.http4s.HttpApp[IO] => IO[A]): IO[A] =
    ProxyApp(client, headerTimeout, bodyIdleTimeout).use(f)

  test("happy path: body round-trips and the connection is released exactly once") {
    run {
      Ref[IO].of(0).flatMap { releases =>
        withApp(probeClient(releases)) { app =>
          for {
            resp <- app(req)
            body <- resp.bodyText.compile.string
            // release happens on the supervised fiber; give it a tick to observe
            _ <- IO.sleep(1.second)
            n <- releases.get
          } yield (body, n)
        }
      }
    }.map { case (bytes, releases) =>
      assertEquals(bytes, payload)
      assertEquals(releases, 1)
    }
  }

  test("body never consumed: connection is released after the grace period") {
    run {
      Ref[IO].of(0).flatMap { releases =>
        withApp(probeClient(releases)) { app =>
          for {
            _ <- app(req) // response is dropped on the floor, body never compiled
            before <- IO.sleep(bodyIdleTimeout - 1.second) *> releases.get
            after <- IO.sleep(2.seconds) *> releases.get
          } yield (before, after)
        }
      }
    }.map { case (before, after) =>
      assertEquals(before, 0, "must not release while still within the grace period")
      assertEquals(after, 1, "must release once the grace period expires")
    }
  }

  test("header timeout: fails promptly, without waiting on the uncancelable upstream acquire") {
    // `Resource.make`'s acquire is uncancelable and JdkHttpClient does the whole round-trip to
    // headers inside one. If we ever block on `fiber.cancel` here, this test hangs for an hour.
    run {
      Ref[IO].of(0).flatMap { releases =>
        withApp(probeClient(releases, acquireDelay = 1.hour)) { app =>
          for {
            start <- IO.monotonic
            outcome <- app(req).attempt
            end <- IO.monotonic
            n <- releases.get
          } yield (outcome, end - start, n)
        }
      }
    }.map { case (outcome, elapsed, releases) =>
      assert(outcome.swap.exists(_.isInstanceOf[TimeoutException]), s"expected TimeoutException, got $outcome")
      assertEquals(elapsed, headerTimeout, "must give up after headerTimeout, not after the upstream answers")
      assertEquals(releases, 0, "nothing has been acquired yet, so there is nothing to release")
    }
  }

  test("late acquisition after a header timeout is still released (the allocated/race hazard)") {
    // Upstream completes acquisition just after we give up. `allocated` + `.timeout` leaks here,
    // because `race` cancels the already-successful acquisition and discards its release action.
    run {
      Ref[IO].of(0).flatMap { releases =>
        withApp(probeClient(releases, acquireDelay = headerTimeout + 1.second)) { app =>
          for {
            start <- IO.monotonic
            outcome <- app(req).attempt
            end <- IO.monotonic
            atTimeout <- releases.get
            afterwards <- IO.sleep(bodyIdleTimeout + 5.seconds) *> releases.get
          } yield {
            assert(outcome.isLeft)
            (end - start, atTimeout, afterwards)
          }
        }
      }
    }.map { case (elapsed, atTimeout, afterwards) =>
      assertEquals(elapsed, headerTimeout)
      assertEquals(atTimeout, 0, "upstream had not answered yet at the moment we gave up")
      assertEquals(afterwards, 1, "the connection acquired after we gave up must still be released")
    }
  }

  test("connection is held open while a slow body is still streaming") {
    // The request fiber returns as soon as headers are in; the downstream client reads the body
    // long afterwards. Releasing at that point would truncate every slow response.
    run {
      Ref[IO].of(0).flatMap { releases =>
        val slowBody = Stream.emits("abcde".getBytes("UTF-8")).covary[IO].metered[IO](1.second)
        withApp(probeClient(releases, body = slowBody)) { app =>
          for {
            response <- app(req)
            reader <- response.bodyText.compile.string.start
            midStream <- IO.sleep(2.seconds) *> releases.get
            body <- reader.joinWithNever
            afterStream <- IO.sleep(1.second) *> releases.get
          } yield {
            assertEquals(body, "abcde", "body was truncated")
            (midStream, afterStream)
          }
        }
      }
    }.map { case (midStream, afterStream) =>
      assertEquals(midStream, 0, "must not release while the downstream client is still reading")
      assertEquals(afterStream, 1, "must release once the body is fully consumed")
    }
  }

  test("a body slower in total than the idle timeout still succeeds while it keeps progressing") {
    // 30 chunks at one per second is 30s total, far beyond bodyIdleTimeout -- but it never stalls.
    // A total cap (`Stream#timeout`) would truncate this; an idle timeout must not.
    run {
      Ref[IO].of(0).flatMap { releases =>
        val steady = Stream.emits("abcdefghijklmnopqrstuvwxyz0123".getBytes("UTF-8")).covary[IO].metered[IO](1.second)
        withApp(probeClient(releases, body = steady)) { app =>
          for {
            response <- app(req)
            body <- response.bodyText.compile.string
            _ <- IO.sleep(1.second)
            n <- releases.get
          } yield (body, n)
        }
      }
    }.map { case (body, releases) =>
      assertEquals(body, "abcdefghijklmnopqrstuvwxyz0123", "a steadily progressing body must not be truncated")
      assertEquals(releases, 1)
    }
  }

  test("reading a body after the exchange was reclaimed fails loudly rather than returning empty") {
    run {
      Ref[IO].of(0).flatMap { releases =>
        withApp(probeClient(releases)) { app =>
          for {
            response <- app(req)
            _ <- IO.sleep(bodyIdleTimeout + 1.second) // exchange is reclaimed while we dawdle
            outcome <- response.bodyText.compile.string.attempt
          } yield outcome
        }
      }
    }.map { outcome =>
      assert(
        outcome.swap.exists(_.isInstanceOf[ProxyApp.ReclaimedException]),
        s"a reclaimed body must fail with ReclaimedException, not read as an empty response: $outcome"
      )
    }
  }

  test("stalled body: stream fails with a timeout and the connection is released") {
    run {
      Ref[IO].of(0).flatMap { releases =>
        val stalling = payloadStream ++ Stream.exec(IO.never[Unit])
        withApp(probeClient(releases, body = stalling)) { app =>
          for {
            resp <- app(req)
            outcome <- resp.body.compile.drain.attempt
            _ <- IO.sleep(1.second)
            n <- releases.get
          } yield (outcome, n)
        }
      }
    }.map { case (outcome, releases) =>
      assert(outcome.isLeft, s"expected the body stream to fail, got $outcome")
      assert(outcome.swap.exists(_.isInstanceOf[TimeoutException]), s"expected TimeoutException, got $outcome")
      assertEquals(releases, 1)
    }
  }

  test("caller cancelled mid-body: connection is released") {
    run {
      Ref[IO].of(0).flatMap { releases =>
        val stalling = payloadStream ++ Stream.exec(IO.never[Unit])
        withApp(probeClient(releases, body = stalling)) { app =>
          for {
            resp <- app(req)
            fiber <- resp.body.compile.drain.start
            _ <- IO.sleep(1.second)
            _ <- fiber.cancel
            _ <- IO.sleep(1.second)
            n <- releases.get
          } yield n
        }
      }
    }.map { releases =>
      assertEquals(releases, 1)
    }
  }

  test("upstream acquisition failure propagates and releases nothing") {
    run {
      Ref[IO].of(0).flatMap { releases =>
        val boom = new RuntimeException("connection refused")
        withApp(probeClient(releases, acquireFailure = Some(boom))) { app =>
          for {
            outcome <- app(req).attempt
            _ <- IO.sleep(1.second)
            n <- releases.get
          } yield (outcome, n)
        }
      }
    }.map { case (outcome, releases) =>
      assert(outcome.isLeft, s"expected the upstream error to propagate, got $outcome")
      assertEquals(outcome.swap.toOption.map(_.getMessage), Some("connection refused"))
      assertEquals(releases, 0)
    }
  }

  test("concurrent requests each release their own connection") {
    run {
      Ref[IO].of(0).flatMap { releases =>
        withApp(probeClient(releases)) { app =>
          for {
            bodies <- List.range(0, 100).parTraverse(_ => app(req).flatMap(_.bodyText.compile.string))
            _ <- IO.sleep(1.second)
            n <- releases.get
          } yield (bodies.count(_ != payload), n)
        }
      }
    }.map { case (bad, releases) =>
      assertEquals(bad, 0)
      assertEquals(releases, 100)
    }
  }

  test("concurrent requests whose bodies are all abandoned are all reclaimed") {
    // The production failure mode: abandoned exchanges accumulating until nothing can connect.
    run {
      Ref[IO].of(0).flatMap { releases =>
        withApp(probeClient(releases)) { app =>
          for {
            _ <- List.range(0, 100).parTraverse_(_ => app(req).void)
            before <- releases.get
            after <- IO.sleep(bodyIdleTimeout + 5.seconds) *> releases.get
          } yield (before, after)
        }
      }
    }.map { case (before, after) =>
      assertEquals(before, 0)
      assertEquals(after, 100, "every abandoned exchange must be reclaimed")
    }
  }

  test("baseline: Client#toHttpApp leaks when the body is never consumed") {
    // Guards against these tests being vacuous: this is the defect ProxyApp exists to fix, and it
    // must still be observable through the same probe.
    run {
      Ref[IO].of(0).flatMap { releases =>
        val app = probeClient(releases).toHttpApp
        app(req) *> IO.sleep(1.hour) *> releases.get
      }
    }.map { releases =>
      assertEquals(releases, 0, "toHttpApp reaches release only through the body stream")
    }
  }

  test("many sequential requests release every connection") {
    run {
      Ref[IO].of(0).flatMap { releases =>
        withApp(probeClient(releases)) { app =>
          List.fill(200)(()).traverse_(_ => app(req).flatMap(_.body.compile.drain)) *>
            IO.sleep(1.second) *> releases.get
        }
      }
    }.map { releases =>
      assertEquals(releases, 200)
    }
  }
}
