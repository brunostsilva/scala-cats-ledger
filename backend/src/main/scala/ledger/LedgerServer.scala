package ledger

import cats.effect.{IO, Ref, Resource}
import com.comcast.ip4s.{Host, Port}
import org.http4s.HttpApp
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.server.Server
import org.http4s.server.middleware.{CORS, RequestId}
import org.typelevel.log4cats.LoggerFactory
import ledger.http.{AccessLog, LedgerRoutes}
import ledger.service.{AuditedLedgerService, LedgerService, LedgerState}

/** The whole application as a `Resource`: state, audited service, middleware and the Ember server.
  * `Main` runs it on port 8080; `LedgerServerSpec` runs the same wiring on a free port.
  */
object LedgerServer:

  def resource(host: Host, port: Port)(using LoggerFactory[IO]): Resource[IO, Server] =
    for
      ref    <- Resource.eval(Ref.of[IO, LedgerState](LedgerState.empty))
      server <- EmberServerBuilder
                  .default[IO]
                  .withHost(host)
                  .withPort(port)
                  .withHttpApp(httpApp(ref))
                  .withLogger(LoggerFactory[IO].getLoggerFromName("ledger.server"))
                  .build
    yield server

  private def httpApp(ref: Ref[IO, LedgerState])(using LoggerFactory[IO]): HttpApp[IO] =
    val service = AuditedLedgerService(
      LedgerService.inMemory[IO](ref),
      LoggerFactory[IO].getLoggerFromName(AuditedLedgerService.LoggerName)
    )
    val routes = new LedgerRoutes[IO](service).routes
    // Request id outermost, so the access log line and the response both carry it.
    RequestId.httpApp(
      AccessLog(LoggerFactory[IO].getLoggerFromName(AccessLog.LoggerName))(
        CORS.policy.withAllowOriginAll(routes).orNotFound
      )
    )
