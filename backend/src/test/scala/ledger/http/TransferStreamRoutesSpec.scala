package ledger.http

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import com.comcast.ip4s.*
import fs2.Stream
import io.circe.jawn.decode
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.typelevel.ci.*
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory
import ledger.domain.*
import ledger.service.{LedgerService, LedgerState}
import JsonCodecs.given

/** `GET /stream/transfers`: NDJSON output, laziness, and release of the storage cursor. */
class TransferStreamRoutesSpec extends CatsEffectSuite:

  private given LoggerFactory[IO] = NoOpFactory[IO]

  private val request = Request[IO](Method.GET, uri"/stream/transfers")

  private def cents(c: Long): Money = Money.fromCents(c).toOption.get

  private def lines(response: Response[IO]): Stream[IO, String] =
    response.body.through(fs2.text.utf8.decode).through(fs2.text.lines).filter(_.nonEmpty)

  /** A storage whose transfer stream holds a "cursor" (acquired and released with bracket, like a
    * database connection) and emits `source` while it is open. Only streaming is implemented.
    */
  private final case class CursorStorage(service: LedgerService[IO], opened: Ref[IO, Int], closed: Ref[IO, Int])

  private def cursorStorage(source: Stream[IO, Transfer]): IO[CursorStorage] =
    (Ref.of[IO, Int](0), Ref.of[IO, Int](0)).mapN { (opened, closed) =>
      val service = new LedgerService[IO]:
        private def unused = IO.raiseError(new NotImplementedError("only streamTransfers is used"))
        def createAccount(owner: String)                               = unused
        def getAccount(id: AccountId)                                  = unused
        def deposit(id: AccountId, amount: Money)                      = unused
        def transfer(from: AccountId, to: AccountId, amount: Money)    = unused
        def transactionsFor(id: AccountId)                             = unused
        def streamTransfers(pageSize: Int): Stream[IO, Transfer] =
          Stream.bracket(opened.update(_ + 1))(_ => closed.update(_ + 1)) >> source
      CursorStorage(service, opened, closed)
    }

  private val sampleFrom = AccountId.fromString("00000000-0000-4000-8000-000000000001").toOption.get
  private val sampleTo   = AccountId.fromString("00000000-0000-4000-8000-000000000002").toOption.get

  private def sampleTransfer(i: Long): Transfer =
    Transfer(UUID.nameUUIDFromBytes(BigInt(i).toByteArray), sampleFrom, sampleTo, cents(i + 1), Instant.EPOCH)

  test("streams every transfer as NDJSON with streaming headers, and no deposits") {
    for
      ref      <- Ref.of[IO, LedgerState](LedgerState.empty)
      service   = LedgerService.inMemory[IO](ref)
      alice    <- service.createAccount("Alice")
      bob      <- service.createAccount("Bob")
      _        <- service.deposit(alice.id, cents(10000))
      _        <- service.transfer(alice.id, bob.id, cents(250))
      _        <- service.transfer(bob.id, alice.id, cents(100))
      response <- new LedgerRoutes[IO](service).routes.orNotFound.run(request)
      body     <- lines(response).compile.toList
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(response.headers.get(ci"Content-Type").map(_.head.value), Some("application/x-ndjson"))
      assert(response.headers.get(ci"Transfer-Encoding").exists(_.head.value.contains("chunked")), response.headers)
      assertEquals(response.headers.get(ci"Content-Length"), None)
      val transfers = body.map(line => decode[Transfer](line).fold(e => fail(s"invalid line $line: $e"), identity))
      assertEquals(transfers.map(t => (t.from, t.to, t.amount)), List((alice.id, bob.id, cents(250)), (bob.id, alice.id, cents(100))))
  }

  test("an empty ledger is a 200 with an empty body") {
    for
      ref      <- Ref.of[IO, LedgerState](LedgerState.empty)
      response <- new LedgerRoutes[IO](LedgerService.inMemory[IO](ref)).routes.orNotFound.run(request)
      body     <- response.body.compile.toList
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(body, Nil)
  }

  test("an endless ledger is streamed lazily, and the cursor is released when the client stops reading") {
    for
      storage  <- cursorStorage(Stream.iterate(0L)(_ + 1).map(sampleTransfer)) // never ends
      response <- new LedgerRoutes[IO](storage.service).routes.orNotFound.run(request)
      firstFew <- lines(response).take(3).compile.toList // would hang if anything buffered the ledger
      opened   <- storage.opened.get
      closed   <- storage.closed.get
    yield
      assertEquals(firstFew.map(line => decode[Transfer](line).map(_.amount)), List(1L, 2L, 3L).map(c => Right(cents(c))))
      assertEquals((opened, closed), (1, 1))
  }

  test("a failure mid-stream ends the body with an error, after the lines already sent, and releases the cursor") {
    val failing = Stream.emits(List(0L, 1L)).map(sampleTransfer) ++ Stream.raiseError[IO](new RuntimeException("storage failed"))
    for
      storage  <- cursorStorage(failing)
      response <- new LedgerRoutes[IO](storage.service).routes.orNotFound.run(request)
      received <- lines(response).attempt.compile.toList
      closed   <- storage.closed.get
    yield
      assertEquals(response.status, Status.Ok) // the status was decided before the failure
      assertEquals(received.collect { case Right(line) => decode[Transfer](line).map(_.amount) }, List(Right(cents(1)), Right(cents(2))))
      assert(received.lastOption.exists(_.isLeft), received)
      assertEquals(closed, 1)
  }

  test("over a real socket, a client that disconnects mid-stream makes the server release the cursor") {
    val server = (storage: CursorStorage) =>
      EmberServerBuilder
        .default[IO]
        .withHost(host"127.0.0.1")
        .withPort(port"0")
        .withHttpApp(new LedgerRoutes[IO](storage.service).routes.orNotFound)
        .build
    for
      storage <- cursorStorage(Stream.iterate(0L)(_ + 1).map(sampleTransfer)) // never ends
      result  <- server(storage).both(EmberClientBuilder.default[IO].build).use { (running, client) =>
                   val get = Request[IO](Method.GET, running.baseUri / "stream" / "transfers")
                   for
                     // Reading 3 lines and stopping closes the response, and with it the connection.
                     firstFew <- client.stream(get).flatMap(lines).take(3).compile.toList
                     // The server only notices on its next write, so wait (bounded) for the release.
                     _        <- eventually(storage.closed.get.map(_ == 1))
                     opened   <- storage.opened.get
                   yield (firstFew.size, opened)
                 }
      closed  <- storage.closed.get
    yield assertEquals((result._1, result._2, closed), (3, 1, 1))
  }

  /** Polls `condition` every 50 ms, failing after 10 s. */
  private def eventually(condition: IO[Boolean]): IO[Unit] =
    def loop: IO[Unit] = condition.ifM(IO.unit, IO.sleep(50.millis) >> loop)
    loop.timeout(10.seconds)
