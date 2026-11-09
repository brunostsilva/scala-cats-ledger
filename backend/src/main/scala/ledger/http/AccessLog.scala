package ledger.http

import java.io.IOException
import scala.concurrent.duration.FiniteDuration
import cats.data.Kleisli
import cats.effect.{Resource, Temporal}
import cats.syntax.all.*
import org.http4s.{HttpApp, Request, Response}
import org.http4s.server.middleware.RequestId
import org.typelevel.log4cats.StructuredLogger

/** One log line per HTTP request: method, path, status, duration, request id and how the response
  * body ended.
  *
  * When the line is written depends on the body:
  *   - a body built in memory (sent with a known length, e.g. a JSON reply) is logged as soon as the
  *     route returns the response, so the request is logged even if the server never gets to send it
  *     (e.g. the client left before the headers were written);
  *   - a streamed body (sent chunked, e.g. `GET /transactions/stream`) is logged when it ends, with
  *     its real duration and outcome: `completed`, `failed` (the body failed after the status was
  *     sent, logged at WARN) or `interrupted` (the connection went away, logged at INFO). If the server
  *     never starts sending it, http4s offers no hook to log it from: a known gap, limited to streamed
  *     responses.
  *
  * Meant to run inside http4s's `RequestId` middleware, which takes the id from the `X-Request-ID`
  * header (or generates one) and returns it to the client, so a user or another service can quote it.
  * Headers, query strings and bodies are not logged, since they can carry personal data.
  */
object AccessLog:

  /** A dedicated logger name, so access logs can be silenced or routed on their own. */
  val LoggerName = "ledger.http.access"

  def apply[F[_]: Temporal](logger: StructuredLogger[F])(app: HttpApp[F]): HttpApp[F] =
    Kleisli { request =>
      Temporal[F].monotonic.flatMap { start =>
        def logNow(status: Int, outcome: Outcome): F[Unit] =
          Temporal[F].monotonic.flatMap(end => log(logger, request, status, end - start, outcome))

        app(request).attempt.flatMap {
          // An exception escaping the app is turned into a 500 by the server, so it is logged as one.
          case Left(error) =>
            logNow(500, Outcome.Failed(error)) *> error.raiseError[F, Response[F]]
          case Right(response) if !response.isChunked =>
            logNow(response.status.code, Outcome.Completed).as(response)
          case Right(response) =>
            val logged = response.body.onFinalizeCase(exit => logNow(response.status.code, Outcome.ofBody(exit)))
            response.withBodyStream(logged).pure[F]
        }
      }
    }

  /** How a response ended, as far as the access log is concerned. */
  private enum Outcome:
    case Completed
    case Failed(error: Throwable)
    case Interrupted(cause: Option[Throwable])

  private object Outcome:
    def ofBody(exit: Resource.ExitCase): Outcome = exit match
      case Resource.ExitCase.Succeeded => Completed
      case Resource.ExitCase.Canceled  => Interrupted(None)
      // A client going away surfaces as an error, not a cancellation: the server only notices when its
      // next write fails (broken pipe, connection reset). That's a closed connection, not a failed
      // response. Storage failures don't come as IOExceptions here (a database driver would raise its
      // own exceptions), so they stay `failed`.
      case Resource.ExitCase.Errored(e: IOException) => Interrupted(Some(e))
      case Resource.ExitCase.Errored(e)              => Failed(e)

  private def describe(e: Throwable): String = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)

  private def log[F[_]](
      logger: StructuredLogger[F],
      request: Request[F],
      status: Int,
      took: FiniteDuration,
      outcome: Outcome
  ): F[Unit] =
    val requestId = request.attributes.lookup(RequestId.requestIdAttrKey).getOrElse("-")
    val method    = request.method.name
    val path      = request.uri.path.renderString
    val (name, detail) = outcome match
      case Outcome.Completed             => ("completed", "")
      case Outcome.Failed(e)             => ("failed", s" (response failed: ${describe(e)})")
      case Outcome.Interrupted(None)     => ("interrupted", " (response interrupted)")
      case Outcome.Interrupted(Some(e))  => ("interrupted", s" (connection closed: ${describe(e)})")
    val context = Map(
      "http.request.id"           -> requestId,
      "http.request.method"       -> method,
      "url.path"                  -> path,
      "http.response.status_code" -> status.toString,
      "http.response.outcome"     -> name,
      "duration_ms"               -> took.toMillis.toString
    )
    val message = s"$method $path -> $status in ${took.toMillis} ms [$requestId]$detail"
    if status >= 500 || name == "failed" then logger.warn(context)(message) else logger.info(context)(message)
