package ledger.http

import cats.data.Kleisli
import cats.effect.{IO, Ref}
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.implicits.*
import org.http4s.server.middleware.RequestId
import org.typelevel.ci.*
import org.typelevel.log4cats.testing.StructuredTestingLogger
import org.typelevel.log4cats.testing.StructuredTestingLogger.{INFO, WARN}
import ledger.service.{LedgerService, LedgerState}
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory

class AccessLogSpec extends CatsEffectSuite:

  /** The service's own DEBUG trace is not what these tests are about. */
  private given LoggerFactory[IO] = NoOpFactory[IO]

  private val unknownId = "00000000-0000-4000-8000-000000000000"

  /** The same middleware stack as `Main`: request id outside, access log inside, real routes. */
  private def withRoutes(logger: StructuredTestingLogger[IO]): IO[HttpApp[IO]] =
    Ref.of[IO, LedgerState](LedgerState.empty).map { ref =>
      RequestId.httpApp(AccessLog(logger)(new LedgerRoutes[IO](LedgerService.inMemory[IO](ref)).routes.orNotFound))
    }

  test("logs method, path, status, duration and the generated request id") {
    val logger = StructuredTestingLogger.impl[IO]()
    for
      app      <- withRoutes(logger)
      response <- app.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/accounts/$unknownId")))
      logged   <- logger.logged
    yield
      val requestId = response.headers.get(ci"X-Request-ID").map(_.head.value)
      assertEquals(response.status, Status.NotFound)
      assert(requestId.isDefined, "the response carries the request id")
      logged match
        case Vector(INFO(message, None, context)) =>
          assertEquals(context.get("http.request.id"), requestId)
          assertEquals(context.get("http.request.method"), Some("GET"))
          assertEquals(context.get("url.path"), Some(s"/accounts/$unknownId"))
          assertEquals(context.get("http.response.status_code"), Some("404"))
          assert(context.get("duration_ms").exists(_.toLongOption.exists(_ >= 0)), context)
          assert(message.startsWith(s"GET /accounts/$unknownId -> 404 in "), message)
        case other => fail(s"expected one INFO line, got $other")
  }

  test("reuses the caller's X-Request-ID") {
    val logger = StructuredTestingLogger.impl[IO]()
    for
      app    <- withRoutes(logger)
      _      <- app.run(Request[IO](Method.GET, uri"/accounts").putHeaders(Header.Raw(ci"X-Request-ID", "support-1234")))
      logged <- logger.logged
    yield assertEquals(logged.flatMap(_.ctx.get("http.request.id")), Vector("support-1234"))
  }

  test("does not log query strings or bodies") {
    val logger = StructuredTestingLogger.impl[IO]()
    for
      app <- withRoutes(logger)
      _ <- app.run(
             Request[IO](Method.POST, Uri.unsafeFromString("/accounts?token=secret-token"))
               .withEntity(Json.obj("owner" -> Json.fromString("Alice Wonderland")))
           )
      logged <- logger.logged
    yield
      assertEquals(logged.flatMap(_.ctx.get("http.response.status_code")), Vector("201"))
      assertEquals(logged.flatMap(_.ctx.get("url.path")), Vector("/accounts"))
      logged.foreach { entry =>
        val text = entry.message + entry.ctx.values.mkString(" ")
        assert(!text.contains("secret-token") && !text.contains("Alice"), text)
      }
  }

  test("an exception escaping the app is logged as a 500 warning and still propagated") {
    val logger  = StructuredTestingLogger.impl[IO]()
    val failing = AccessLog(logger)(Kleisli((_: Request[IO]) => IO.raiseError[Response[IO]](new RuntimeException("boom"))))
    for
      result <- failing.run(Request[IO](Method.GET, uri"/accounts")).attempt
      logged <- logger.logged
    yield
      assert(result.isLeft)
      logged match
        case Vector(WARN(message, None, context)) =>
          assertEquals(context.get("http.response.status_code"), Some("500"))
          assertEquals(context.get("http.request.id"), Some("-"))
          assert(message.startsWith("GET /accounts -> 500 in "), message)
        case other => fail(s"expected one WARN line, got $other")
  }
