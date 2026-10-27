package ledger

import cats.effect.{ExitCode, IO, IOApp, Ref}
import com.comcast.ip4s.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.CORS
import org.http4s.implicits.*
import ledger.http.LedgerRoutes
import ledger.service.{LedgerService, LedgerState}

object Main extends IOApp:

  def run(args: List[String]): IO[ExitCode] =
    for
      ref     <- Ref.of[IO, LedgerState](LedgerState.empty)
      service  = LedgerService.inMemory[IO](ref)
      routes   = new LedgerRoutes[IO](service).routes
      httpApp  = CORS.policy.withAllowOriginAll(routes).orNotFound
      _       <- EmberServerBuilder
                   .default[IO]
                   .withHost(host"0.0.0.0")
                   .withPort(port"8080")
                   .withHttpApp(httpApp)
                   .build
                   .useForever
    yield ExitCode.Success
