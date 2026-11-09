package ledger.http

import scala.concurrent.duration.*
import cats.data.Kleisli
import cats.effect.{IO, Ref}
import fs2.Stream
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

  /** The service's own log lines are not what these tests are about. */
  private given LoggerFactory[IO] = NoOpFactory[IO]

  private val unknownId = "00000000-0000-4000-8000-000000000000"

  /** Runs a request and reads the whole response body, as the server does: the line is logged then. */
  private def send(app: HttpApp[IO], request: Request[IO]): IO[Response[IO]] =
    app.run(request).flatTap(_.body.compile.drain)

  /** The same middleware stack as `Main`: request id outside, access log inside, real routes. */
  private def withRoutes(logger: StructuredTestingLogger[IO]): IO[HttpApp[IO]] =
    Ref.of[IO, LedgerState](LedgerState.empty).flatMap { ref =>
      LedgerRoutes.make[IO](LedgerService.inMemory[IO](ref)).map { ledgerRoutes =>
        RequestId.httpApp(AccessLog(logger)(ledgerRoutes.routes.orNotFound))
      }
    }

  test("logs method, path, status, duration and the generated request id") {
    val logger = StructuredTestingLogger.impl[IO]()
    for
      app      <- withRoutes(logger)
      response <- send(app, Request[IO](Method.GET, Uri.unsafeFromString(s"/accounts/$unknownId")))
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
          assertEquals(context.get("http.response.outcome"), Some("completed"))
          assert(context.get("duration_ms").exists(_.toLongOption.exists(_ >= 0)), context)
          assert(message.startsWith(s"GET /accounts/$unknownId -> 404 in "), message)
        case other => fail(s"expected one INFO line, got $other")
  }

  test("reuses the caller's X-Request-ID") {
    val logger = StructuredTestingLogger.impl[IO]()
    for
      app    <- withRoutes(logger)
      _      <- send(app, Request[IO](Method.GET, uri"/accounts").putHeaders(Header.Raw(ci"X-Request-ID", "support-1234")))
      logged <- logger.logged
    yield assertEquals(logged.flatMap(_.ctx.get("http.request.id")), Vector("support-1234"))
  }

  test("does not log query strings or bodies") {
    val logger = StructuredTestingLogger.impl[IO]()
    for
      app <- withRoutes(logger)
      _ <- send(
             app,
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
          assertEquals(context.get("http.response.outcome"), Some("failed"))
          assert(message.startsWith("GET /accounts -> 500 in "), message)
        case other => fail(s"expected one WARN line, got $other")
  }

  /** An app answering 200 with the given body, wrapped in the access log. */
  private def streaming(logger: StructuredTestingLogger[IO], body: Stream[IO, Byte]): HttpApp[IO] =
    AccessLog(logger)(Kleisli((_: Request[IO]) => IO.pure(Response[IO](Status.Ok).withBodyStream(body))))

  private val oneLine: Stream[IO, Byte] = Stream.emits("first line\n".getBytes.toIndexedSeq)

  test("a body that fails after the status was sent is logged as a failed 200 warning") {
    val logger = StructuredTestingLogger.impl[IO]()
    val app    = streaming(logger, oneLine ++ Stream.raiseError[IO](new RuntimeException("storage failed")))
    for
      response <- app.run(Request[IO](Method.GET, uri"/transactions/stream"))
      before   <- logger.logged
      drained  <- response.body.compile.drain.attempt
      logged   <- logger.logged
    yield
      assertEquals(before, Vector.empty, "nothing is logged before the body ends")
      assert(drained.isLeft)
      logged match
        case Vector(WARN(message, None, context)) =>
          assertEquals(context.get("http.response.status_code"), Some("200"))
          assertEquals(context.get("http.response.outcome"), Some("failed"))
          assert(message.endsWith("(response failed: storage failed)"), message)
        case other => fail(s"expected one WARN line, got $other")
  }

  test("a slow body is logged when it ends, with the time it took to send it") {
    val logger = StructuredTestingLogger.impl[IO]()
    val app    = streaming(logger, oneLine ++ Stream.sleep_[IO](150.millis) ++ oneLine)
    for
      _      <- send(app, Request[IO](Method.GET, uri"/transactions/stream"))
      logged <- logger.logged
    yield logged match
      case Vector(INFO(_, None, context)) =>
        assertEquals(context.get("http.response.outcome"), Some("completed"))
        assert(context.get("duration_ms").exists(_.toLong >= 150), context)
      case other => fail(s"expected one INFO line, got $other")
  }
