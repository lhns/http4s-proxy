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
import scala.util.control.NoStackTrace

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
  /** Internal control-flow signal: the exchange gave up waiting to be read and is unwinding its
    * `use` block. It never reaches a caller -- by the time it is raised the response has already
    * been published -- so it carries no stack trace.
    */
  private object Unconsumed extends Exception("response body was never consumed") with NoStackTrace

  /** Raised to whoever pulls a response body after its exchange has already been reclaimed.
    *
    * Releasing an upstream exchange cancels its body publisher, so without this a late reader
    * would observe a silently truncated -- typically empty -- body. Corruption is worse than the
    * leak this guards against, so the read fails instead.
    */
  final class ReclaimedException private[proxy] (message: String) extends IllegalStateException(message)

  /** Who owns the response body. The reader and the supervising fiber both try to claim it, and
    * exactly one wins: a `Ref` decides atomically, because racing two signals against each other
    * leaves a window in which the fiber releases the exchange just as the reader starts reading,
    * which is silent truncation.
    */
  private sealed trait Phase
  private case object Unclaimed extends Phase
  private case object Reading extends Phase
  private case object Abandoned extends Phase

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

  /** Allocates a `Supervisor` to own the in-flight exchanges. **Allocate this once, at
    * application scope.** Closing the returned `Resource` cancels every exchange still in
    * flight, so using it per request truncates responses under load; if you already have a
    * supervisor of the right lifetime, use [[withSupervisor]] instead.
    */
  def apply[F[_]](
                   client: Client[F],
                   headerTimeout: FiniteDuration,
                   bodyIdleTimeout: FiniteDuration
                 )(implicit F: Async[F]): Resource[F, HttpApp[F]] =
    Supervisor[F].map(withSupervisor(client, _, headerTimeout, bodyIdleTimeout))

  /** As [[apply]], but with the ownership of the in-flight exchanges made explicit: they are
    * supervised by `supervisor`, and are cancelled when it is closed.
    */
  def withSupervisor[F[_]](
                            client: Client[F],
                            supervisor: Supervisor[F],
                            headerTimeout: FiniteDuration,
                            bodyIdleTimeout: FiniteDuration
                          )(implicit F: Async[F]): HttpApp[F] =
    Kleisli { request =>
      for {
        published <- F.deferred[Either[Throwable, Response[F]]]
        phase <- F.ref[Phase](Unclaimed)
        pulled <- F.deferred[Unit]
        done <- F.deferred[Unit]
        fiber <- supervisor.supervise(
          client
            .run(request)
            .use { response =>
              published.complete(Right(response)) *>
                // hold the exchange open only while the body is actually in flight
                pulled.get.timeoutTo(
                  bodyIdleTimeout,
                  // Nobody has read the body in time. Claim it -- unless a reader just did, in
                  // which case stand down and wait for them as if the timeout had not fired.
                  phase
                    .modify {
                      case Unclaimed => (Abandoned, true)
                      case claimed => (claimed, false)
                    }
                    .ifM(F.raiseError[Unit](Unconsumed), pulled.get)
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
        // Claim the body for reading, unless the fiber already gave up on it.
        claim = phase
          .modify {
            case Abandoned => (Abandoned, false)
            case _ => (Reading, true)
          }
          .ifM(
            pulled.complete(()).void,
            F.raiseError[Unit](new ReclaimedException("upstream exchange was already reclaimed"))
          )
      } yield response.withBodyStream(
        (Stream.exec(claim) ++ response.body)
          .through(idleTimeout(bodyIdleTimeout))
          .onFinalizeWeak(done.complete(()).void)
      )
    }
}