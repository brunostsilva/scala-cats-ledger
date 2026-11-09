package ledger.http

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*
import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import com.comcast.ip4s.*
import fs2.Stream
import io.circe.Json
import io.circe.jawn.{decode, parse}
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.ci.*
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory
import org.typelevel.log4cats.testing.StructuredTestingLogger
import org.typelevel.log4cats.testing.StructuredTestingLogger.INFO
import ledger.domain.*
import ledger.service.{LedgerService, LedgerState, PageSize, TransactionQuery}
import JsonCodecs.given

/** `GET /transactions/stream`: NDJSON output, query parameters, the stream limit, laziness, and
  * release of the storage cursor however the stream ends.
  */
class TransactionStreamRoutesSpec extends CatsEffectSuite:

  private given LoggerFactory[IO] = NoOpFactory[IO]

  private def cents(c: Long): Money = Money.fromCents(c).toOption.get

  private def app(service: LedgerService[IO], settings: TransactionStreamSettings = TransactionStreamSettings.Default) =
    TransactionStream.make[IO](service, settings).map(stream => new LedgerRoutes[IO](service, stream).routes.orNotFound)

  private def get(query: String = ""): Request[IO] =
    Request[IO](Method.GET, Uri.unsafeFromString(s"/transactions/stream$query"))

  private def lines(response: Response[IO]): Stream[IO, String] =
    response.body.through(fs2.text.utf8.decode).through(fs2.text.lines).filter(_.nonEmpty)

  private def json(line: String): Json = parse(line).fold(e => fail(s"invalid JSON line $line: $e"), identity)

  private def seq(line: String): Option[Long] = json(line).hcursor.get[Long]("seq").toOption

  private def endLine(count: Long, lastSeq: Long): String = s"""{"end":true,"count":$count,"lastSeq":$lastSeq}"""

  private final case class Ledger(service: LedgerService[IO], alice: Account, bob: Account)

  /** A real ledger: Alice deposits 100.00 (seq 1), then sends Bob 2.50 (seq 2 for Alice, 3 for Bob). */
  private def ledger: IO[Ledger] =
    for
      ref     <- Ref.of[IO, LedgerState](LedgerState.empty)
      service  = LedgerService.inMemory[IO](ref)
      alice   <- service.createAccount("Alice")
      bob     <- service.createAccount("Bob")
      _       <- service.deposit(alice.id, cents(10000))
      _       <- service.transfer(alice.id, bob.id, cents(250))
    yield Ledger(service, alice, bob)

  /** A storage whose stream holds a "cursor" (acquired and released with bracket, like a database
    * connection) and emits `source` while it is open. Only streaming is implemented.
    */
  private final case class CursorStorage(service: LedgerService[IO], opened: Ref[IO, Int], closed: Ref[IO, Int])

  private def cursorStorage(source: Stream[IO, LedgerEntry]): IO[CursorStorage] =
    (Ref.of[IO, Int](0), Ref.of[IO, Int](0)).mapN { (opened, closed) =>
      val service = new LedgerService[IO]:
        private def unused = IO.raiseError(new NotImplementedError("only streamTransactions is used"))
        def createAccount(owner: String)                            = unused
        def getAccount(id: AccountId)                               = unused
        def deposit(id: AccountId, amount: Money)                   = unused
        def transfer(from: AccountId, to: AccountId, amount: Money) = unused
        def transactionsFor(id: AccountId)                          = unused
        def streamTransactions(query: TransactionQuery): Stream[IO, LedgerEntry] =
          Stream.bracket(opened.update(_ + 1))(_ => closed.update(_ + 1)) >> source
      CursorStorage(service, opened, closed)
    }

  private val sampleAccount = AccountId.fromString("00000000-0000-4000-8000-000000000001").toOption.get

  private def sampleEntry(i: Long): LedgerEntry =
    val amount = cents(i + 1)
    LedgerEntry(
      i + 1,
      Transaction(UUID.nameUUIDFromBytes(BigInt(i).toByteArray), sampleAccount, None, TransactionType.Deposit, amount, amount, Instant.EPOCH)
    )

  private val endless: Stream[IO, LedgerEntry] = Stream.iterate(0L)(_ + 1).map(sampleEntry)

  test("streams the ledger as NDJSON with streaming headers, ending with a StreamEnd line") {
    for
      fx       <- ledger
      routes   <- app(fx.service)
      response <- routes.run(get())
      body     <- lines(response).compile.toList
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(response.headers.get(ci"Content-Type").map(_.head.value), Some("application/x-ndjson"))
      assert(response.headers.get(ci"Transfer-Encoding").exists(_.head.value.contains("chunked")), response.headers)
      assertEquals(response.headers.get(ci"Content-Length"), None)
      val entries = body.init.map(line => decode[Transaction](line).fold(e => fail(s"invalid line $line: $e"), identity))
      assertEquals(body.init.map(seq), List(Some(1L), Some(2L), Some(3L)))
      assertEquals(entries.map(t => (t.accountId, t.txType, t.amount)), List(
        (fx.alice.id, TransactionType.Deposit, cents(10000)),
        (fx.alice.id, TransactionType.Transfer, cents(250)),
        (fx.bob.id, TransactionType.Transfer, cents(250))
      ))
      assertEquals(body.last, endLine(count = 3, lastSeq = 3))
  }

  test("an empty ledger is a 200 with only the StreamEnd line") {
    for
      ref      <- Ref.of[IO, LedgerState](LedgerState.empty)
      routes   <- app(LedgerService.inMemory[IO](ref))
      response <- routes.run(get())
      body     <- lines(response).compile.toList
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(body, List(endLine(count = 0, lastSeq = 0)))
  }

  test("account and after select the entries, and the StreamEnd line says where to resume") {
    for
      fx       <- ledger
      routes   <- app(fx.service)
      ofAlice  <- routes.run(get(s"?account=${fx.alice.id.asString}&after=1&pageSize=1")).flatMap(lines(_).compile.toList)
      ofBob    <- routes.run(get(s"?account=${fx.bob.id.asString}")).flatMap(lines(_).compile.toList)
      caughtUp <- routes.run(get("?after=10")).flatMap(lines(_).compile.toList)
    yield
      assertEquals(ofAlice.map(line => seq(line).getOrElse(-1L)), List(2L, -1L))
      assertEquals(ofAlice.last, endLine(count = 1, lastSeq = 2))
      assertEquals(ofBob.init.flatMap(seq), List(3L))
      assertEquals(caughtUp, List(endLine(count = 0, lastSeq = 10)))
  }

  test("invalid parameters are a 400, before anything is read from storage") {
    val invalid = List("?account=not-a-uuid", "?after=-1", "?after=soon", "?pageSize=0", "?pageSize=1001", "?pageSize=lots")
    for
      storage   <- cursorStorage(endless)
      routes    <- app(storage.service)
      responses <- invalid.traverse(query => routes.run(get(query)))
      messages  <- responses.traverse(_.as[String])
      opened    <- storage.opened.get
    yield
      assertEquals(responses.map(_.status), List.fill(invalid.size)(Status.BadRequest))
      messages.foreach(message => assert(json(message).hcursor.get[String]("message").isRight, message))
      assertEquals(opened, 0)
  }

  test("an unknown account is a 404, and refused requests don't take a stream slot") {
    val oneStream = TransactionStreamSettings(PageSize.Default, maxConcurrentStreams = 1)
    for
      fx       <- ledger
      routes   <- app(fx.service, oneStream)
      unknown  <- AccountId.random[IO]
      notFound <- routes.run(get(s"?account=${unknown.asString}"))
      _        <- routes.run(get("?after=-1"))
      stream   <- routes.run(get())
      body     <- lines(stream).compile.toList
    yield
      assertEquals(notFound.status, Status.NotFound)
      assertEquals(stream.status, Status.Ok)
      assertEquals(body.last, endLine(count = 3, lastSeq = 3))
  }

  test("while the limit of streams is running a request gets a 503 with Retry-After, until one ends") {
    val oneStream = TransactionStreamSettings(PageSize.Default, maxConcurrentStreams = 1)
    for
      storage <- cursorStorage(Stream.emit(sampleEntry(0)) ++ Stream.never[IO]) // one entry, then stays open
      routes  <- app(storage.service, oneStream)
      first   <- routes.run(get())
      reading <- Deferred[IO, Unit]
      client  <- lines(first).evalTap(_ => reading.complete(()).void).compile.drain.start
      _       <- reading.get // the first stream is running, so it counts against the limit
      second  <- routes.run(get())
      _       <- client.cancel // the first client goes away and its stream ends
      third   <- routes.run(get())
      started <- lines(third).take(1).compile.toList
    yield
      assertEquals(first.status, Status.Ok)
      assertEquals(second.status, Status.ServiceUnavailable)
      assertEquals(second.headers.get(ci"Retry-After").map(_.head.value), Some("1"))
      assertEquals(third.status, Status.Ok)
      assertEquals(started.size, 1)
  }

  test("a response whose body never starts doesn't hold a stream slot") {
    val oneStream = TransactionStreamSettings(PageSize.Default, maxConcurrentStreams = 1)
    for
      storage   <- cursorStorage(endless)
      routes    <- app(storage.service, oneStream)
      // Never read, as when the server fails to write the headers to a client that already left.
      abandoned <- routes.run(get())
      next      <- routes.run(get())
      started   <- lines(next).take(1).compile.toList
    yield
      assertEquals(abandoned.status, Status.Ok)
      assertEquals(next.status, Status.Ok)
      assertEquals(started.size, 1)
  }

  test("an endless ledger is streamed lazily, and the cursor is released when the client stops reading") {
    for
      storage  <- cursorStorage(endless)
      routes   <- app(storage.service)
      response <- routes.run(get())
      firstFew <- lines(response).take(3).compile.toList // would hang if anything buffered the ledger
      opened   <- storage.opened.get
      closed   <- storage.closed.get
    yield
      assertEquals(firstFew.map(seq), List(Some(1L), Some(2L), Some(3L)))
      assertEquals((opened, closed), (1, 1))
  }

  test("a failure mid-stream keeps the lines already sent, has no StreamEnd line, and releases the cursor") {
    val failing = Stream.emits(List(0L, 1L)).map(sampleEntry) ++ Stream.raiseError[IO](new RuntimeException("storage failed"))
    for
      storage  <- cursorStorage(failing)
      routes   <- app(storage.service)
      response <- routes.run(get())
      received <- lines(response).attempt.compile.toList
      closed   <- storage.closed.get
    yield
      assertEquals(response.status, Status.Ok) // the status was sent before the failure
      val sent = received.collect { case Right(line) => line }
      assertEquals(sent.map(seq), List(Some(1L), Some(2L)))
      assert(!sent.exists(_.contains("\"end\"")), "a stream cut short must not look complete")
      assert(received.lastOption.exists(_.isLeft), received)
      assertEquals(closed, 1)
  }

  test("over a real socket, a client that disconnects mid-stream releases the cursor and is logged as interrupted") {
    val accessLog = StructuredTestingLogger.impl[IO]()
    for
      storage <- cursorStorage(endless)
      routes  <- app(storage.service)
      server   = EmberServerBuilder
                   .default[IO]
                   .withHost(host"127.0.0.1")
                   .withPort(port"0")
                   .withHttpApp(AccessLog(accessLog)(routes))
                   .build
      result  <- server.both(EmberClientBuilder.default[IO].build).use { (running, client) =>
                   val request = Request[IO](Method.GET, running.baseUri / "transactions" / "stream")
                   for
                     // Reading 3 lines and stopping closes the response, and with it the connection.
                     firstFew <- client.stream(request).flatMap(lines).take(3).compile.toList
                     // The server only notices on its next write, so wait (bounded) for the release.
                     _        <- eventually(storage.closed.get.map(_ == 1))
                     _        <- eventually(accessLog.logged.map(_.nonEmpty))
                     opened   <- storage.opened.get
                   yield (firstFew.size, opened)
                 }
      closed  <- storage.closed.get
      logged  <- accessLog.logged
    yield
      assertEquals((result._1, result._2, closed), (3, 1, 1))
      // A client going away is a closed connection (INFO), not a failed response (WARN).
      logged match
        case Vector(INFO(_, None, context)) =>
          assertEquals(context.get("http.response.outcome"), Some("interrupted"))
          assertEquals(context.get("http.response.status_code"), Some("200"))
        case other => fail(s"expected one INFO access log line, got $other")
  }

  /** Polls `condition` every 50 ms, failing after 10 s. */
  private def eventually(condition: IO[Boolean]): IO[Unit] =
    def loop: IO[Unit] = condition.ifM(IO.unit, IO.sleep(50.millis) >> loop)
    loop.timeout(10.seconds)
