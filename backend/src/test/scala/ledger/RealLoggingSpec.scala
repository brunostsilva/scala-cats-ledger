package ledger

import scala.jdk.CollectionConverters.*
import cats.effect.IO
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.comcast.ip4s.*
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

/** Runs the server with the real logging stack, exactly as `Main` does (log4cats → SLF4J → Logback
  * with `logback.xml`), so every line it logs is printed in the `sbt test` output, in CI too.
  * A capturing appender on the root logger lets the test check what Logback actually received.
  */
class RealLoggingSpec extends CatsEffectSuite:

  private given LoggerFactory[IO] = Slf4jFactory.create[IO]

  /** Keeps every event, with its MDC copied at logging time (Logback otherwise reads it lazily). */
  private final class Capture extends ListAppender[ILoggingEvent]:
    override protected def append(event: ILoggingEvent): Unit =
      event.prepareForDeferredProcessing()
      super.append(event)

  private def post(client: Client[IO], uri: Uri, body: Json): IO[(Status, Json)] =
    client.run(Request[IO](Method.POST, uri).withEntity(body)).use(r => r.as[Json].map(json => (r.status, json)))

  test("every layer logs through SLF4J and Logback, with structured fields in the MDC and no owner names") {
    val context = org.slf4j.LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    val root    = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
    val capture = new Capture
    capture.setContext(context)
    capture.start()
    root.addAppender(capture)

    val run =
      LedgerServer.resource(host"127.0.0.1", port"0").both(EmberClientBuilder.default[IO].build).use { (server, client) =>
        val base = server.baseUri
        for
          alice    <- post(client, base / "accounts", Json.obj("owner" -> Json.fromString("Alice Wonderland")))
          bob      <- post(client, base / "accounts", Json.obj("owner" -> Json.fromString("Bob")))
          aliceId   = alice._2.hcursor.get[String]("id").toOption.get
          bobId     = bob._2.hcursor.get[String]("id").toOption.get
          deposit  <- post(client, base / "accounts" / aliceId / "deposit", Json.obj("amount" -> Json.fromBigDecimal(BigDecimal("10.50"))))
          transfer <- post(
                        client,
                        base / "transfers",
                        Json.obj(
                          "from"   -> Json.fromString(bobId),
                          "to"     -> Json.fromString(aliceId),
                          "amount" -> Json.fromBigDecimal(BigDecimal("5"))
                        )
                      )
        yield (aliceId, deposit._1, transfer._1)
      }

    run.guarantee(IO(root.detachAppender(capture)).void).map { (aliceId, depositStatus, transferStatus) =>
      assertEquals(depositStatus, Status.Ok)
      assertEquals(transferStatus, Status.UnprocessableContent) // Bob has no funds
      val events = capture.list.asScala.toList
      def from(logger: String) = events.filter(_.getLoggerName == logger)
      def mdc(event: ILoggingEvent) = event.getMDCPropertyMap.asScala.toMap

      assert(from("ledger.server").exists(_.getFormattedMessage.startsWith("Ember-Server service bound")), events)
      assert(from("ledger.service").exists(_.getFormattedMessage == s"Creating deposit transaction on account $aliceId"), events)
      assert(
        from("ledger.audit").exists(e => mdc(e).get("event").contains("deposit.completed") && mdc(e).get("amount").contains("10.50")),
        events.map(e => (e.getFormattedMessage, mdc(e)))
      )
      assert(from("ledger.audit").exists(e => mdc(e).get("reason").contains("insufficient_funds")), events)
      assert(
        from("ledger.http.access").nonEmpty &&
          from("ledger.http.access").forall(e => mdc(e).get("http.request.id").exists(_.nonEmpty)),
        events.map(e => (e.getFormattedMessage, mdc(e)))
      )
      assert(!events.exists(e => (e.getFormattedMessage +: mdc(e).values.toList).exists(_.contains("Alice"))), events)
    }
  }
