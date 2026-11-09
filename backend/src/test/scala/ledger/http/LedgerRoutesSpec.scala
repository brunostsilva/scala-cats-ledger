package ledger.http

import cats.effect.{IO, Ref}
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.implicits.*
import ledger.service.{LedgerService, LedgerState}
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory

/** The HTTP API end to end: real routes over the real in-memory service, no mocks. */
class LedgerRoutesSpec extends CatsEffectSuite:

  /** The service's own log lines are not what these tests are about. */
  private given LoggerFactory[IO] = NoOpFactory[IO]

  private val unknownId = "00000000-0000-4000-8000-000000000000"

  private def newApp: IO[HttpApp[IO]] =
    Ref.of[IO, LedgerState](LedgerState.empty).map { ref =>
      new LedgerRoutes[IO](LedgerService.inMemory[IO](ref)).routes.orNotFound
    }

  private def call(app: HttpApp[IO], method: Method, path: String, body: Option[Json] = None): IO[(Status, Json)] =
    val request = Request[IO](method, Uri.unsafeFromString(path))
    app.run(body.fold(request)(request.withEntity(_))).flatMap(resp => resp.as[Json].map(json => (resp.status, json)))

  private def createAccount(app: HttpApp[IO], owner: String): IO[String] =
    call(app, Method.POST, "/accounts", Some(Json.obj("owner" -> Json.fromString(owner))))
      .map((_, json) => json.hcursor.get[String]("id").toOption.get)

  private def deposit(app: HttpApp[IO], id: String, amount: String): IO[(Status, Json)] =
    call(app, Method.POST, s"/accounts/$id/deposit", Some(Json.obj("amount" -> Json.fromBigDecimal(BigDecimal(amount)))))

  private def transfer(app: HttpApp[IO], from: String, to: String, amount: String): IO[(Status, Json)] =
    call(
      app,
      Method.POST,
      "/transfers",
      Some(
        Json.obj(
          "from"   -> Json.fromString(from),
          "to"     -> Json.fromString(to),
          "amount" -> Json.fromBigDecimal(BigDecimal(amount))
        )
      )
    )

  private def message(json: Json): String = json.hcursor.get[String]("message").toOption.getOrElse("")
  private def balance(json: Json): BigDecimal = json.hcursor.get[BigDecimal]("balance").toOption.get

  test("POST /accounts creates an account with a zero balance") {
    for
      app             <- newApp
      (status, json)  <- call(app, Method.POST, "/accounts", Some(Json.obj("owner" -> Json.fromString("Alice"))))
    yield
      assertEquals(status, Status.Created)
      assertEquals(json.hcursor.get[String]("owner"), Right("Alice"))
      assertEquals(balance(json), BigDecimal(0))
      assert(json.hcursor.get[String]("id").isRight)
  }

  test("GET /accounts/{id} returns the account") {
    for
      app            <- newApp
      id             <- createAccount(app, "Alice")
      (status, json) <- call(app, Method.GET, s"/accounts/$id")
    yield
      assertEquals(status, Status.Ok)
      assertEquals(json.hcursor.get[String]("id"), Right(id))
      assertEquals(json.hcursor.get[String]("owner"), Right("Alice"))
  }

  test("GET /accounts/{id} returns 404 for an unknown account and 400 for an invalid id") {
    for
      app                  <- newApp
      (missing, missingJs) <- call(app, Method.GET, s"/accounts/$unknownId")
      (invalid, invalidJs) <- call(app, Method.GET, "/accounts/nope")
    yield
      assertEquals(missing, Status.NotFound)
      assertEquals(message(missingJs), s"Account not found: $unknownId")
      assertEquals(invalid, Status.BadRequest)
      assertEquals(message(invalidJs), "Invalid account id: nope")
  }

  test("POST /accounts/{id}/deposit adds a decimal amount to the balance") {
    for
      app            <- newApp
      id             <- createAccount(app, "Alice")
      _              <- deposit(app, id, "10.50")
      (status, json) <- deposit(app, id, "0.25")
    yield
      assertEquals(status, Status.Ok)
      assertEquals(balance(json), BigDecimal("10.75"))
  }

  test("POST /accounts/{id}/deposit rejects negative amounts and unknown accounts") {
    for
      app                    <- newApp
      id                     <- createAccount(app, "Alice")
      (negative, negativeJs) <- deposit(app, id, "-5")
      (missing, _)           <- deposit(app, unknownId, "5")
      (invalid, _)           <- deposit(app, "nope", "5")
    yield
      assertEquals(negative, Status.UnprocessableContent)
      assert(message(negativeJs).startsWith("Amount cannot be negative"), message(negativeJs))
      assertEquals(missing, Status.NotFound)
      assertEquals(invalid, Status.BadRequest)
  }

  test("POST /transfers moves funds and returns both updated accounts") {
    for
      app            <- newApp
      alice          <- createAccount(app, "Alice")
      bob            <- createAccount(app, "Bob")
      _              <- deposit(app, alice, "100")
      (status, json) <- transfer(app, alice, bob, "30")
    yield
      assertEquals(status, Status.Ok)
      assertEquals(json.hcursor.downField("from").get[BigDecimal]("balance"), Right(BigDecimal(70)))
      assertEquals(json.hcursor.downField("to").get[BigDecimal]("balance"), Right(BigDecimal(30)))
  }

  test("POST /transfers rejects insufficient funds and transfers to the same account") {
    for
      app                    <- newApp
      alice                  <- createAccount(app, "Alice")
      bob                    <- createAccount(app, "Bob")
      _                      <- deposit(app, alice, "10")
      (tooMuch, tooMuchJson) <- transfer(app, alice, bob, "10.01")
      (self, selfJson)       <- transfer(app, alice, alice, "1")
    yield
      assertEquals(tooMuch, Status.UnprocessableContent)
      assert(message(tooMuchJson).contains("insufficient funds"), message(tooMuchJson))
      assertEquals(self, Status.UnprocessableContent)
      assertEquals(message(selfJson), s"Cannot transfer to the same account: $alice")
  }

  test("POST /transfers returns 404 for an unknown account and 400 for invalid input") {
    for
      app                  <- newApp
      alice                <- createAccount(app, "Alice")
      _                    <- deposit(app, alice, "10")
      (missing, _)         <- transfer(app, alice, unknownId, "1")
      (invalid, invalidJs) <- transfer(app, alice, "nope", "1")
      (negative, _)        <- transfer(app, alice, unknownId, "-1")
    yield
      assertEquals(missing, Status.NotFound)
      assertEquals(invalid, Status.BadRequest)
      assertEquals(message(invalidJs), "Invalid transfer request")
      assertEquals(negative, Status.BadRequest)
  }

  test("GET /accounts/{id}/transactions lists the history, most recent first") {
    for
      app            <- newApp
      alice          <- createAccount(app, "Alice")
      bob            <- createAccount(app, "Bob")
      _              <- deposit(app, alice, "100")
      _              <- transfer(app, alice, bob, "40")
      (status, json) <- call(app, Method.GET, s"/accounts/$alice/transactions")
    yield
      assertEquals(status, Status.Ok)
      val txs = json.asArray.getOrElse(Vector.empty)
      assertEquals(txs.map(_.hcursor.get[String]("txType").toOption), Vector(Some("Transfer"), Some("Deposit")))
      assertEquals(txs.head.hcursor.get[String]("counterpartyId"), Right(bob))
      assertEquals(txs.head.hcursor.get[BigDecimal]("balanceAfter"), Right(BigDecimal(60)))
      assertEquals(txs(1).hcursor.downField("counterpartyId").focus, Some(Json.Null))
  }

  test("GET /accounts/{id}/transactions returns 404 for an unknown account") {
    for
      app         <- newApp
      (status, _) <- call(app, Method.GET, s"/accounts/$unknownId/transactions")
    yield assertEquals(status, Status.NotFound)
  }

  test("unknown routes return 404") {
    for
      app      <- newApp
      response <- app.run(Request[IO](Method.GET, uri"/nope"))
    yield assertEquals(response.status, Status.NotFound)
  }

  test("a malformed JSON body fails the request with a message failure") {
    for
      app    <- newApp
      result <- app.run(Request[IO](Method.POST, uri"/accounts").withEntity("not json")).attempt
    yield assert(result.left.exists(_.isInstanceOf[MessageFailure]), result)
  }
