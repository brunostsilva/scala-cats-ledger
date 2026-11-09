package ledger

import cats.effect.{IO, Ref, Resource}
import com.comcast.ip4s.{Host, Port}
import org.http4s.HttpApp
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.server.Server
import org.http4s.server.middleware.{CORS, RequestId}
import org.typelevel.log4cats.LoggerFactory
import ledger.http.{AccessLog, LedgerRoutes, TransactionStream, TransactionStreamSettings}
import ledger.service.{AuditedLedgerService, LedgerService, LedgerState}

/** The whole application as a `Resource`: state, audited service, middleware and the Ember server.
  * `Main` runs it on port 8080; `LedgerServerSpec` runs the same wiring on a free port.
  */
object LedgerServer:

  def resource(
      host: Host,
      port: Port,
      streamSettings: TransactionStreamSettings = TransactionStreamSettings.Default
  )(using LoggerFactory[IO]): Resource[IO, Server] =
    for
      ref     <- Resource.eval(Ref.of[IO, LedgerState](LedgerState.empty))
      httpApp <- Resource.eval(app(ref, streamSettings))
      server  <- EmberServerBuilder
                   .default[IO]
                   .withHost(host)
                   .withPort(port)
                   .withHttpApp(httpApp)
                   .withLogger(LoggerFactory[IO].getLoggerFromName("ledger.server"))
                   .build
    yield server

  private def app(ref: Ref[IO, LedgerState], streamSettings: TransactionStreamSettings)(using
      LoggerFactory[IO]
  ): IO[HttpApp[IO]] =
    val service = AuditedLedgerService(
      LedgerService.inMemory[IO](ref),
      LoggerFactory[IO].getLoggerFromName(AuditedLedgerService.LoggerName)
    )
    TransactionStream.make[IO](service, streamSettings).map { transactionStream =>
      val routes = new LedgerRoutes[IO](service, transactionStream).routes
      // Request id outermost, so the access log line and the response both carry it.
      RequestId.httpApp(
        AccessLog(LoggerFactory[IO].getLoggerFromName(AccessLog.LoggerName))(
          CORS.policy.withAllowOriginAll(routes).orNotFound
        )
      )
    }
