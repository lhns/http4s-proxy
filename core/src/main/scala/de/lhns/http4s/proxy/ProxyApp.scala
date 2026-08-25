package de.lhns.http4s.proxy

import cats.data.Kleisli
// NB: cats.effect.kernel.syntax, not cats.effect.syntax -- the latter lives in the cats-effect
// core artifact, i.e. the IO runtime, which a library at F[_] must not put on its consumers.
import cats.effect.kernel.syntax.all._
import cats.effect.kernel.{Async, Resource, Temporal}
import cats.effect.std.Supervisor
import cats.syntax.all._
import fs2.{Pipe, Pull, Stream}
import org.http4s.client.Client
import org.http4s.{HttpApp, Response}

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration

/** Like `Client#toHttpApp`, but the upstream connection is guaranteed to be released even if the
  * response body is never consumed, and both the header phase and the body phase are bounded.
  *
  * The upstream exchange is owned by a supervised fiber which holds it open inside `Resource#use`
  * for exactly as long as the response body is in flight. `use` releases on completion, error and
  * cancelation alike, so there is no path on which the connection is retained.
  *
  * `bodyIdleTimeout` bounds *lack of progress*, not total duration: a large body streaming to a
  * slow downstream client is fine, a body that stalls is not. Never being read at all is just the
  * first special case of no progress, which is why one setting covers both -- it is enforced by the
  * fiber before the first pull, and by the stream itself between chunks.
  *
  * That fiber necessarily outlives the request: this returns as soon as the response headers are
  * in, while the body is streamed afterwards by whoever consumes the response. The supervisor must
  * therefore live as long as the application, which is why this is a `Resource` -- closing it
  * cancels in-flight exchanges, so it must not be closed per request.
  */
object ProxyApp {
  private object Unconsumed extends Exception("response body was never consumed")

  /** Raised if the body is pulled after the exchange has already been reclaimed. Releasing an
    * upstream exchange cancels its body publisher, so a late reader would otherwise observe a
    * silently truncated (empty) body -- corruption is worse than the leak this class prevents.
    */
  private object Reclaimed extends Exception("upstream exchange was already reclaimed")

  /** Fails the stream if no chunk arrives within `duration` of the previous one. Unlike
    * `Stream#timeout`, a stream that keeps making progress may run for as long as it likes.
    */
  private def idleTimeout[F[_]: Temporal, A](duration: FiniteDuration): Pipe[F, A, A] =
    _.pull
      .timed { timedPull =>
        def go(current: Pull.Timed[F, A]): Pull[F, A, Unit] =
          current.timeout(duration) >> current.uncons.flatMap {
            case Some((Right(chunk), next)) => Pull.output(chunk) >> go(next)
            case Some((Left(_), _)) =>
              Pull.raiseError[F](new TimeoutException(s"upstream body stalled for $duration"))
            case None => Pull.done
          }

        go(timedPull)
      }
      .stream

  def apply[F[_]](
                   client: Client[F],
                   headerTimeout: FiniteDuration,
                   bodyIdleTimeout: FiniteDuration
                 )(implicit F: Async[F]): Resource[F, HttpApp[F]] =
    Supervisor[F].map { supervisor =>
      Kleisli { request =>
        for {
          published <- F.deferred[Either[Throwable, Response[F]]]
          pulled <- F.deferred[Unit]
          abandoned <- F.deferred[Unit]
          done <- F.deferred[Unit]
          fiber <- supervisor.supervise(
            client
              .run(request)
              .use { response =>
                published.complete(Right(response)) *>
                  // hold the exchange open only while the body is actually in flight
                  pulled.get.timeoutTo(
                    bodyIdleTimeout,
                    abandoned.complete(()) *> F.raiseError[Unit](Unconsumed)
                  ) *>
                  done.get
              }
              .handleErrorWith(throwable => published.complete(Left(throwable)).void)
          )
          // Pruning must not block. A `Resource.make` acquire is uncancelable (`applyFull` discards
          // the `Poll`), so for any client that acquires the response that way, `fiber.cancel` waits
          // for the very upstream we just gave up on. The idle timeout in the fiber is the backstop
          // if the cancelation never lands.
          prune = supervisor.supervise(fiber.cancel).void
          response <- published.get.rethrow
            .timeoutTo(
              headerTimeout,
              F.raiseError[Response[F]](
                new TimeoutException(s"no response headers from upstream within $headerTimeout")
              )
            )
            .onError { case _ => prune }
            .onCancel(prune)
        } yield response.withBodyStream(
          (Stream.exec(pulled.complete(()).void) ++ response.body)
            .interruptWhen(abandoned.get.as(Left(Reclaimed): Either[Throwable, Unit]))
            .through(idleTimeout(bodyIdleTimeout))
            .onFinalizeWeak(done.complete(()).void)
        )
      }
    }
}