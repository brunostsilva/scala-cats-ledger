package ledger

import cats.effect.{IO, Resource}
import com.comcast.ip4s.*
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.ci.*
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.testing.StructuredTestingLogger

/** End to end over real HTTP: the same wiring as `Main` (`LedgerServer.resource`) on a free port,
  * called with an HTTP client, with every log line recorded.
  */
class LedgerServerSpec extends CatsEffectSuite:

  private final case class Running(base: Uri, client: Client[IO], logger: StructuredTestingLogger[IO])
  private final case class Reply(status: Status, headers: Headers, json: Json)

  /** Port 0: the operating system picks a free one, and `baseUri` reports it. */
  private def running: Resource[IO, Running] =
    val logger = StructuredTestingLogger.impl[IO]()
    given LoggerFactory[IO] = RecordingLoggerFactory(logger)
    for
      server <- LedgerServer.resource(host"127.0.0.1", port"0")
      client <- EmberClientBuilder.default[IO].build
    yield Running(server.baseUri, client, logger)

  /** A POST as the browser frontend sends it, with an `Origin` header. */
  private def post(app: Running, uri: Uri, body: Json): IO[Reply] =
    val request = Request[IO](Method.POST, uri)
      .withEntity(body)
      .putHeaders(Header.Raw(ci"Origin", "http://localhost:5173"))
    app.client.run(request).use(response => response.as[Json].map(json => Reply(response.status, response.headers, json)))

  private def createAccount(app: Running, owner: String): IO[String] =
    post(app, app.base / "accounts", Json.obj("owner" -> Json.fromString(owner)))
      .map(_.json.hcursor.get[String]("id").toOption.get)

  private def header(reply: Reply, name: CIString): Option[String] = reply.headers.get(name).map(_.head.value)

  test("a deposit and a transfer go through routes, service and audit over real HTTP") {
    running.use { app =>
      for
        alice    <- createAccount(app, "Alice Wonderland")
        bob      <- createAccount(app, "Bob")
        deposit  <- post(app, app.base / "accounts" / alice / "deposit", Json.obj("amount" -> Json.fromBigDecimal(BigDecimal("10.50"))))
        transfer <- post(
                      app,
                      app.base / "transfers",
                      Json.obj(
                        "from"   -> Json.fromString(alice),
                        "to"     -> Json.fromString(bob),
                        "amount" -> Json.fromBigDecimal(BigDecimal("2"))
                      )
                    )
        logged   <- app.logger.logged
      yield
        assertEquals(deposit.status, Status.Ok)
        assertEquals(deposit.json.hcursor.get[BigDecimal]("balance").toOption, Some(BigDecimal("10.50")))
        assertEquals(transfer.status, Status.Ok)
        val messages = logged.map(_.message)
        assert(messages.contains(s"Creating deposit transaction on account $alice"), messages)
        assert(messages.contains(s"Deposit of 10.50 into account $alice"), messages)
        assert(messages.contains(s"Creating transfer transaction from account $alice to $bob"), messages)
        assert(messages.contains(s"Transfer of 2.00 from account $alice to $bob"), messages)
        assert(!logged.exists(entry => (entry.message +: entry.ctx.values.toSeq).exists(_.contains("Alice"))), messages)
    }
  }

  test("responses carry the request id and CORS headers, and the access log records the same id") {
    running.use { app =>
      for
        reply  <- post(app, app.base / "accounts", Json.obj("owner" -> Json.fromString("Carol")))
        logged <- app.logger.logged
      yield
        val requestId = header(reply, ci"X-Request-ID")
        assertEquals(reply.status, Status.Created)
        assert(requestId.isDefined, reply.headers)
        assertEquals(header(reply, ci"Access-Control-Allow-Origin"), Some("*"))
        val access = logged.filter(_.ctx.contains("http.request.id"))
        assertEquals(access.flatMap(_.ctx.get("http.request.id")), requestId.toVector)
        assertEquals(access.flatMap(_.ctx.get("http.response.status_code")), Vector("201"))
    }
  }

  test("a same-account transfer is a 422, audited as rejected, and never reaches the service") {
    running.use { app =>
      for
        alice  <- createAccount(app, "Alice")
        reply  <- post(
                    app,
                    app.base / "transfers",
                    Json.obj(
                      "from"   -> Json.fromString(alice),
                      "to"     -> Json.fromString(alice),
                      "amount" -> Json.fromBigDecimal(BigDecimal("1"))
                    )
                  )
        logged <- app.logger.logged
      yield
        assertEquals(reply.status, Status.UnprocessableContent)
        assertEquals(logged.flatMap(_.ctx.get("reason")), Vector("same_account"))
        assert(!logged.exists(_.message.startsWith("Creating transfer")), logged.map(_.message))
    }
  }

  test("GET /stream/transfers streams the transfers made through the API as chunked NDJSON") {
    running.use { app =>
      for
        alice <- createAccount(app, "Alice")
        bob   <- createAccount(app, "Bob")
        _     <- post(app, app.base / "accounts" / alice / "deposit", Json.obj("amount" -> Json.fromBigDecimal(BigDecimal("10"))))
        _     <- post(
                   app,
                   app.base / "transfers",
                   Json.obj(
                     "from"   -> Json.fromString(alice),
                     "to"     -> Json.fromString(bob),
                     "amount" -> Json.fromBigDecimal(BigDecimal("3"))
                   )
                 )
        reply <- app.client
                   .run(Request[IO](Method.GET, app.base / "stream" / "transfers"))
                   .use(response => response.bodyText.compile.string.map(body => (response.status, response.headers, body)))
      yield
        val (status, headers, body) = reply
        assertEquals(status, Status.Ok)
        assertEquals(headers.get(ci"Content-Type").map(_.head.value), Some("application/x-ndjson"))
        assert(headers.get(ci"Transfer-Encoding").exists(_.head.value.contains("chunked")), headers)
        val lines = body.linesIterator.filter(_.nonEmpty).toList
        assertEquals(lines.size, 1, body)
        val transfer = io.circe.jawn.parse(lines.head).toOption.get.hcursor
        assertEquals(transfer.get[String]("from").toOption, Some(alice))
        assertEquals(transfer.get[String]("to").toOption, Some(bob))
        assertEquals(transfer.get[BigDecimal]("amount").toOption, Some(BigDecimal("3.00")))
    }
  }
