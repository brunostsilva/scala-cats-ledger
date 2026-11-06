package ledger.http

import scala.concurrent.duration.FiniteDuration
import cats.data.Kleisli
import cats.effect.Temporal
import cats.syntax.all.*
import org.http4s.{HttpApp, Request}
import org.http4s.server.middleware.RequestId
import org.typelevel.log4cats.StructuredLogger

/** One log line per HTTP request: method, path, status, duration and request id.
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
      for
        start    <- Temporal[F].monotonic
        result   <- app(request).attempt
        end      <- Temporal[F].monotonic
        // An exception escaping the app is turned into a 500 by the server, so it is logged as one.
        _        <- log(logger, request, result.fold(_ => 500, _.status.code), end - start)
        response <- result.liftTo[F]
      yield response
    }

  private def log[F[_]](logger: StructuredLogger[F], request: Request[F], status: Int, took: FiniteDuration): F[Unit] =
    val requestId = request.attributes.lookup(RequestId.requestIdAttrKey).getOrElse("-")
    val method    = request.method.name
    val path      = request.uri.path.renderString
    val context = Map(
      "http.request.id"           -> requestId,
      "http.request.method"       -> method,
      "url.path"                  -> path,
      "http.response.status_code" -> status.toString,
      "duration_ms"               -> took.toMillis.toString
    )
    val message = s"$method $path -> $status in ${took.toMillis} ms [$requestId]"
    if status >= 500 then logger.warn(context)(message) else logger.info(context)(message)
