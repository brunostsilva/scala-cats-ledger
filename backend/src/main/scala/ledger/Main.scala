package ledger

import cats.effect.{ExitCode, IO, IOApp}
import cats.effect.std.Console
import com.comcast.ip4s.*
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory
import ledger.http.TransactionStreamSettings

object Main extends IOApp:

  private given LoggerFactory[IO] = Slf4jFactory.create[IO]

  // Configuration is validated before anything starts: a bad value stops the app with a clear
  // message instead of silently disabling logging or streaming with a nonsensical setting.
  def run(args: List[String]): IO[ExitCode] =
    IO(
      for
        _              <- LogFormat.validate(LogFormat.configured)
        streamSettings <- TransactionStreamSettings.fromEnv(sys.env)
      yield streamSettings
    ).flatMap {
      case Left(error)           => Console[IO].errorln(error).as(ExitCode.Error)
      case Right(streamSettings) => LedgerServer.resource(host"0.0.0.0", port"8080", streamSettings).useForever
    }
