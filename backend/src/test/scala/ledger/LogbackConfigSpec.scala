package ledger

import scala.jdk.CollectionConverters.*
import ch.qos.logback.classic.{AsyncAppender, LoggerContext}
import ch.qos.logback.classic.encoder.JsonEncoder
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.core.OutputStreamAppender
import ch.qos.logback.core.status.{ErrorStatus, InfoStatus, Status, WarnStatus}
import munit.FunSuite

/** `logback.xml`, loaded into a fresh context per test so the JVM-wide logging setup is untouched. */
class LogbackConfigSpec extends FunSuite:

  /** Loads `logback.xml` with the given properties, runs the checks, then stops the async workers. */
  private def withConfig(properties: (String, String)*)(checks: LoggerContext => Unit): Unit =
    val context = new LoggerContext()
    properties.foreach((key, value) => context.putProperty(key, value))
    val configurator = new JoranConfigurator()
    configurator.setContext(context)
    configurator.doConfigure(getClass.getResource("/logback.xml"))
    try checks(context)
    finally context.stop()

  private def errors(context: LoggerContext): List[String] =
    context.getStatusManager.getCopyOfStatusList.asScala.toList
      .filter(_.getLevel == Status.ERROR)
      .map(_.getMessage)

  private def root(context: LoggerContext) = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)

  private def rootAppenders(context: LoggerContext): List[String] =
    root(context).iteratorForAppenders().asScala.map(_.getName).toList

  /** The root appender must be asynchronous and lossless, and wrap the given console appender. */
  private def assertAsyncWrapping(context: LoggerContext, name: String): OutputStreamAppender[?] =
    val async = root(context).getAppender(s"async-$name").asInstanceOf[AsyncAppender]
    assertEquals(async.getDiscardingThreshold, 0, "audit lines (INFO) must never be dropped")
    assert(!async.isNeverBlock, "a full queue must apply backpressure instead of losing lines")
    async.getAppender(name).asInstanceOf[OutputStreamAppender[?]]

  test("logs readable text at INFO by default, through a lossless async appender") {
    withConfig() { context =>
      assertEquals(errors(context), Nil)
      assertEquals(rootAppenders(context), List("async-text"))
      assert(!assertAsyncWrapping(context, "text").getEncoder.isInstanceOf[JsonEncoder])
      assertEquals(root(context).getLevel.toString, "INFO")
    }
  }

  test("only Logback's own errors reach the console, so warnings don't dump its status on startup") {
    withConfig() { context =>
      val listeners = context.getStatusManager.getCopyOfStatusListenerList.asScala.toList
      assert(listeners.exists(_.isInstanceOf[ErrorsOnlyStatusListener]), listeners)
    }
  }

  test("ErrorsOnlyStatusListener prints errors and ignores info and warnings") {
    val captured = new java.io.ByteArrayOutputStream()
    val original = System.err
    System.setErr(new java.io.PrintStream(captured, true))
    try
      val listener = new ErrorsOnlyStatusListener
      listener.addStatusEvent(new InfoStatus("an info status", this))
      listener.addStatusEvent(new WarnStatus("a warning status", this))
      listener.addStatusEvent(new ErrorStatus("an error status", this))
    finally System.setErr(original)
    val printed = captured.toString
    assert(printed.contains("Logback error: an error status"), printed)
    assert(!printed.contains("an info status") && !printed.contains("a warning status"), printed)
  }

  test("LOG_FORMAT=json switches to one JSON object per line") {
    withConfig("LOG_FORMAT" -> "json") { context =>
      assertEquals(errors(context), Nil)
      assertEquals(rootAppenders(context), List("async-json"))
      assert(assertAsyncWrapping(context, "json").getEncoder.isInstanceOf[JsonEncoder])
    }
  }

  test("an unknown LOG_FORMAT leaves Logback without any appender, which is why Main refuses it") {
    withConfig("LOG_FORMAT" -> "JSON") { context =>
      assertEquals(rootAppenders(context), Nil)
      val problems = context.getStatusManager.getCopyOfStatusList.asScala.filter(_.getLevel >= Status.WARN)
      assert(problems.nonEmpty, "Logback only reports it as an internal status")
    }
  }

  test("LOG_LEVEL changes the root level") {
    withConfig("LOG_LEVEL" -> "DEBUG") { context =>
      assertEquals(root(context).getLevel.toString, "DEBUG")
    }
  }
