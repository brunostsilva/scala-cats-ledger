package ledger

import scala.jdk.CollectionConverters.*
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.JsonEncoder
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.core.OutputStreamAppender
import ch.qos.logback.core.status.Status
import munit.FunSuite

/** `logback.xml`, loaded into a fresh context per test so the JVM-wide logging setup is untouched. */
class LogbackConfigSpec extends FunSuite:

  private def configure(properties: (String, String)*): LoggerContext =
    val context = new LoggerContext()
    properties.foreach((key, value) => context.putProperty(key, value))
    val configurator = new JoranConfigurator()
    configurator.setContext(context)
    configurator.doConfigure(getClass.getResource("/logback.xml"))
    context

  private def errors(context: LoggerContext): List[String] =
    context.getStatusManager.getCopyOfStatusList.asScala.toList
      .filter(_.getLevel == Status.ERROR)
      .map(_.getMessage)

  private def rootAppenders(context: LoggerContext): List[String] =
    context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).iteratorForAppenders().asScala.map(_.getName).toList

  test("logs readable text at INFO by default") {
    val context = configure()
    assertEquals(errors(context), Nil)
    assertEquals(rootAppenders(context), List("text"))
    assertEquals(context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getLevel.toString, "INFO")
  }

  test("LOG_FORMAT=json switches to one JSON object per line") {
    val context = configure("LOG_FORMAT" -> "json")
    assertEquals(errors(context), Nil)
    assertEquals(rootAppenders(context), List("json"))
    val appender = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender("json")
    assert(appender.asInstanceOf[OutputStreamAppender[?]].getEncoder.isInstanceOf[JsonEncoder])
  }

  test("LOG_LEVEL changes the root level") {
    val context = configure("LOG_LEVEL" -> "DEBUG")
    assertEquals(context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getLevel.toString, "DEBUG")
  }
