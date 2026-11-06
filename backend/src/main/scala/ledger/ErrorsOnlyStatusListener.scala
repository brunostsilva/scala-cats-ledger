package ledger

import ch.qos.logback.core.status.{Status, StatusListener}

/** Prints Logback's own status messages to stderr only when they are errors (see `logback.xml`).
  *
  * Without a registered listener, Logback dumps its whole internal status on startup as soon as
  * loading the configuration raises a warning, which the unused appender of the other `LOG_FORMAT`
  * always does. Logback's built-in console listeners print every status, and `NopStatusListener`
  * would also hide real errors.
  */
final class ErrorsOnlyStatusListener extends StatusListener:

  override def addStatusEvent(status: Status): Unit =
    if status.getLevel >= Status.ERROR then
      System.err.println(s"Logback error: ${status.getMessage}")
      Option(status.getThrowable).foreach(_.printStackTrace())
