package ledger.service

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite
import org.typelevel.log4cats.testing.StructuredTestingLogger
import org.typelevel.log4cats.testing.StructuredTestingLogger.{INFO, LogMessage}
import ledger.domain.*

class AuditedLedgerServiceSpec extends CatsEffectSuite:

  private val owner = "Alice Wonderland"

  private final case class Fixture(service: LedgerService[IO], logger: StructuredTestingLogger[IO])

  private def newFixture: IO[Fixture] =
    Ref.of[IO, LedgerState](LedgerState.empty).map { ref =>
      val logger = StructuredTestingLogger.impl[IO]()
      Fixture(AuditedLedgerService(LedgerService.inMemory[IO](ref), logger), logger)
    }

  private def cents(c: Long): Money = Money.fromCents(c).toOption.get

  private def events(logged: Vector[LogMessage]): Vector[String] = logged.flatMap(_.ctx.get("event"))

  private def assertNoPersonalData(logged: Vector[LogMessage]): Unit =
    logged.foreach { entry =>
      assert(!entry.message.contains(owner), s"owner name in message: ${entry.message}")
      assert(!entry.ctx.values.exists(_.contains(owner)), s"owner name in context: ${entry.ctx}")
      assert(!entry.ctx.keys.exists(_.contains("balance")), s"balance in context: ${entry.ctx}")
    }

  test("account creation is audited with the account id but not the owner's name") {
    for
      fx      <- newFixture
      account <- fx.service.createAccount(owner)
      logged  <- fx.logger.logged
    yield
      assertEquals(
        logged,
        Vector(
          INFO(
            s"Account ${account.id.asString} created",
            None,
            Map("event" -> "account.created", "account.id" -> account.id.asString)
          )
        )
      )
      assertNoPersonalData(logged)
  }

  test("a deposit is audited with the amount, and the result is returned unchanged") {
    for
      fx      <- newFixture
      account <- fx.service.createAccount(owner)
      result  <- fx.service.deposit(account.id, cents(1050))
      logged  <- fx.logger.logged
    yield
      assertEquals(result, Right(account.copy(balance = cents(1050))))
      assertEquals(
        logged.last.ctx,
        Map("event" -> "deposit.completed", "account.id" -> account.id.asString, "amount" -> "10.50")
      )
      assertNoPersonalData(logged)
  }

  test("a transfer is audited with both account ids") {
    for
      fx      <- newFixture
      alice   <- fx.service.createAccount(owner)
      bob     <- fx.service.createAccount("Bob")
      _       <- fx.service.deposit(alice.id, cents(500))
      _       <- fx.service.transfer(alice.id, bob.id, cents(200))
      logged  <- fx.logger.logged
    yield
      assertEquals(
        logged.last.ctx,
        Map(
          "event"           -> "transfer.completed",
          "from.account.id" -> alice.id.asString,
          "to.account.id"   -> bob.id.asString,
          "amount"          -> "2.00"
        )
      )
      assertNoPersonalData(logged)
  }

  test("a rejected transfer is audited with a stable reason code") {
    for
      fx      <- newFixture
      alice   <- fx.service.createAccount(owner)
      bob     <- fx.service.createAccount("Bob")
      result  <- fx.service.transfer(alice.id, bob.id, cents(100))
      logged  <- fx.logger.logged
    yield
      assert(result.isLeft)
      assertEquals(logged.last.ctx.get("event"), Some("transfer.rejected"))
      assertEquals(logged.last.ctx.get("reason"), Some("insufficient_funds"))
      assert(logged.last.message.endsWith("rejected: insufficient_funds"), logged.last.message)
      assertNoPersonalData(logged)
  }

  test("a deposit into an unknown account is audited as rejected") {
    for
      fx      <- newFixture
      unknown <- AccountId.random[IO]
      _       <- fx.service.deposit(unknown, cents(100))
      logged  <- fx.logger.logged
    yield assertEquals(
      logged.map(_.ctx),
      Vector(
        Map(
          "event"      -> "deposit.rejected",
          "reason"     -> "account_not_found",
          "account.id" -> unknown.asString,
          "amount"     -> "1.00"
        )
      )
    )
  }

  test("reads are not audited") {
    for
      fx      <- newFixture
      account <- fx.service.createAccount(owner)
      _       <- fx.service.getAccount(account.id)
      _       <- fx.service.transactionsFor(account.id)
      logged  <- fx.logger.logged
    yield assertEquals(events(logged), Vector("account.created"))
  }

  test("every ledger error has a reason code") {
    val id = AccountId.fromString("00000000-0000-4000-8000-000000000000").toOption.get
    val reasons = List(
      LedgerError.AccountNotFound(id),
      LedgerError.InsufficientFunds(id, Money.zero, cents(1)),
      LedgerError.SameAccountTransfer(id),
      LedgerError.InvalidAmount("Amount overflow")
    ).map(AuditedLedgerService.reason)
    assertEquals(reasons, List("account_not_found", "insufficient_funds", "same_account", "invalid_amount"))
  }
