package ledger

import cats.effect.IO
import org.typelevel.log4cats.{LoggerFactory, SelfAwareStructuredLogger}
import org.typelevel.log4cats.testing.StructuredTestingLogger

/** A `LoggerFactory` that hands out one recording logger, whatever the logger name. */
object RecordingLoggerFactory:

  def apply(logger: StructuredTestingLogger[IO]): LoggerFactory[IO] =
    new LoggerFactory[IO]:
      def getLoggerFromName(name: String): SelfAwareStructuredLogger[IO] = logger
      def fromName(name: String): IO[SelfAwareStructuredLogger[IO]]     = IO.pure(logger)
