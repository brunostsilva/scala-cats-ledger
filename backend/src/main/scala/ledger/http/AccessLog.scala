package ledger.http

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
  * The line is written when the response body ends, not when the route returns the response, so a
  * streamed response (e.g. `GET /stream/transfers`) is logged with its real duration and with its
  * outcome: `completed`, `failed` (the body failed after the status was sent, logged at WARN) or
  * `interrupted` (usually the client going away).
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
        def logNow(status: Int, exit: Resource.ExitCase): F[Unit] =
          Temporal[F].monotonic.flatMap(end => log(logger, request, status, end - start, exit))

        app(request).attempt.flatMap {
          // An exception escaping the app is turned into a 500 by the server, so it is logged as one.
          case Left(error) =>
            logNow(500, Resource.ExitCase.Errored(error)) *> error.raiseError[F, Response[F]]
          case Right(response) =>
            response.withBodyStream(response.body.onFinalizeCase(exit => logNow(response.status.code, exit))).pure[F]
        }
      }
    }

  private def log[F[_]](
      logger: StructuredLogger[F],
      request: Request[F],
      status: Int,
      took: FiniteDuration,
      exit: Resource.ExitCase
  ): F[Unit] =
    val requestId = request.attributes.lookup(RequestId.requestIdAttrKey).getOrElse("-")
    val method    = request.method.name
    val path      = request.uri.path.renderString
    val (outcome, detail) = exit match
      case Resource.ExitCase.Succeeded => ("completed", "")
      case Resource.ExitCase.Errored(e) =>
        ("failed", s" (response failed: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)})")
      case Resource.ExitCase.Canceled => ("interrupted", " (response interrupted)")
    val context = Map(
      "http.request.id"           -> requestId,
      "http.request.method"       -> method,
      "url.path"                  -> path,
      "http.response.status_code" -> status.toString,
      "http.response.outcome"     -> outcome,
      "duration_ms"               -> took.toMillis.toString
    )
    val message = s"$method $path -> $status in ${took.toMillis} ms [$requestId]$detail"
    if status >= 500 || outcome == "failed" then logger.warn(context)(message) else logger.info(context)(message)
