package ledger

import cats.effect.{ExitCode, IO, IOApp, Ref}
import cats.effect.std.Console
import com.comcast.ip4s.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.{CORS, RequestId}
import org.http4s.implicits.*
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory
import ledger.http.{AccessLog, LedgerRoutes}
import ledger.service.{AuditedLedgerService, LedgerService, LedgerState}

object Main extends IOApp:

  private given LoggerFactory[IO] = Slf4jFactory.create[IO]

  def run(args: List[String]): IO[ExitCode] =
    IO(LogFormat.validate(LogFormat.configured)).flatMap {
      case Left(error) => Console[IO].errorln(error).as(ExitCode.Error)
      case Right(_)    => serve
    }

  private def serve: IO[ExitCode] =
    for
      ref     <- Ref.of[IO, LedgerState](LedgerState.empty)
      service  = AuditedLedgerService(
                   LedgerService.inMemory[IO](ref),
                   LoggerFactory[IO].getLoggerFromName(AuditedLedgerService.LoggerName)
                 )
      routes   = new LedgerRoutes[IO](service).routes
      // Request id outermost, so the access log line and the response both carry it.
      httpApp  = RequestId.httpApp(
                   AccessLog(LoggerFactory[IO].getLoggerFromName(AccessLog.LoggerName))(
                     CORS.policy.withAllowOriginAll(routes).orNotFound
                   )
                 )
      _       <- EmberServerBuilder
                   .default[IO]
                   .withHost(host"0.0.0.0")
                   .withPort(port"8080")
                   .withHttpApp(httpApp)
                   .withLogger(LoggerFactory[IO].getLoggerFromName("ledger.server"))
                   .build
                   .useForever
    yield ExitCode.Success
