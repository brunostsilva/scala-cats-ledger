package ledger

import cats.effect.{ExitCode, IO, IOApp}
import cats.effect.std.Console
import com.comcast.ip4s.*
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

object Main extends IOApp:

  private given LoggerFactory[IO] = Slf4jFactory.create[IO]

  def run(args: List[String]): IO[ExitCode] =
    IO(LogFormat.validate(LogFormat.configured)).flatMap {
      case Left(error) => Console[IO].errorln(error).as(ExitCode.Error)
      case Right(_)    => LedgerServer.resource(host"0.0.0.0", port"8080").useForever
    }
