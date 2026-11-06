package ledger.service

import cats.effect.{IO, Ref, Sync}
import cats.syntax.all.*
import ledger.domain.*
import munit.CatsEffectSuite
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory
import org.typelevel.log4cats.testing.StructuredTestingLogger
import ledger.RecordingLoggerFactory

class LedgerServiceSpec extends CatsEffectSuite:

  /** Silent by default; the last test records the log lines with its own factory. */
  private given LoggerFactory[IO] = NoOpFactory[IO]

  private def newService: IO[LedgerService[IO]] =
    Ref.of[IO, LedgerState](LedgerState.empty).map(LedgerService.inMemory[IO])

  private def cents(c: Long): Money = Money.fromCents(c).toOption.get

  test("deposit increases balance") {
    for
      service <- newService
      account <- service.createAccount("Alice")
      amount   = cents(1000)
      updated <- service.deposit(account.id, amount)
    yield assertEquals(updated, Right(account.copy(balance = amount)))
  }

  test("deposit fails when the resulting balance would overflow") {
    for
      service <- newService
      alice   <- service.createAccount("Alice")
      _       <- service.deposit(alice.id, cents(Long.MaxValue))
      result  <- service.deposit(alice.id, cents(1))
    yield result match
      case Left(LedgerError.InvalidAmount(_)) => ()
      case other                              => fail(s"expected InvalidAmount, got $other")
  }

  test("deposit fails for an unknown account") {
    for
      service <- newService
      unknown <- AccountId.random[IO]
      result  <- service.deposit(unknown, cents(100))
    yield result match
      case Left(LedgerError.AccountNotFound(_)) => ()
      case other                                => fail(s"expected AccountNotFound, got $other")
  }

  test("transfer moves funds between accounts") {
    for
      service <- newService
      alice   <- service.createAccount("Alice")
      bob     <- service.createAccount("Bob")
      amount   = cents(500)
      _       <- service.deposit(alice.id, amount)
      result  <- service.transfer(alice.id, bob.id, amount)
    yield result match
      case Right((from, to)) =>
        assertEquals(from.balance, Money.zero)
        assertEquals(to.balance, amount)
      case Left(err) => fail(s"expected success, got $err")
  }

  test("transfer fails with insufficient funds") {
    for
      service <- newService
      alice   <- service.createAccount("Alice")
      bob     <- service.createAccount("Bob")
      result  <- service.transfer(alice.id, bob.id, cents(100))
    yield result match
      case Left(LedgerError.InsufficientFunds(_, _, _)) => ()
      case other                                        => fail(s"expected InsufficientFunds, got $other")
  }

  test("transfer fails for an unknown counterparty") {
    for
      service <- newService
      alice   <- service.createAccount("Alice")
      unknown <- AccountId.random[IO]
      _       <- service.deposit(alice.id, cents(100))
      result  <- service.transfer(alice.id, unknown, cents(50))
    yield result match
      case Left(LedgerError.AccountNotFound(_)) => ()
      case other                                => fail(s"expected AccountNotFound, got $other")
  }

  test("transfer to the same account is rejected") {
    for
      service <- newService
      alice   <- service.createAccount("Alice")
      result  <- service.transfer(alice.id, alice.id, cents(50))
    yield result match
      case Left(LedgerError.SameAccountTransfer(_)) => ()
      case other                                    => fail(s"expected SameAccountTransfer, got $other")
  }

  test("getAccount finds created accounts and reports unknown ones") {
    for
      service <- newService
      alice   <- service.createAccount("Alice")
      unknown <- AccountId.random[IO]
      found   <- service.getAccount(alice.id)
      missing <- service.getAccount(unknown)
    yield
      assertEquals(found, Right(alice))
      assertEquals(missing, Left(LedgerError.AccountNotFound(unknown)))
  }

  test("a failed transfer leaves both balances and histories untouched") {
    for
      service <- newService
      alice   <- service.createAccount("Alice")
      bob     <- service.createAccount("Bob")
      _       <- service.deposit(alice.id, cents(100))
      result  <- service.transfer(alice.id, bob.id, cents(101))
      aliceAfter <- service.getAccount(alice.id)
      bobTxs     <- service.transactionsFor(bob.id)
    yield
      assertEquals(result, Left(LedgerError.InsufficientFunds(alice.id, cents(100), cents(101))))
      assertEquals(aliceAfter.map(_.balance.cents), Right(100L))
      assertEquals(bobTxs, Right(Nil))
  }

  test("a transfer is recorded on both sides with the counterparty and resulting balances") {
    for
      service  <- newService
      alice    <- service.createAccount("Alice")
      bob      <- service.createAccount("Bob")
      _        <- service.deposit(alice.id, cents(100))
      _        <- service.transfer(alice.id, bob.id, cents(30))
      aliceTxs <- service.transactionsFor(alice.id)
      bobTxs   <- service.transactionsFor(bob.id)
    yield (aliceTxs, bobTxs) match
      case (Right(out :: _), Right(in :: Nil)) =>
        assertEquals(out.txType, TransactionType.Transfer)
        assertEquals(out.counterpartyId, Some(bob.id))
        assertEquals(out.balanceAfter.cents, 70L)
        assertEquals(in.txType, TransactionType.Transfer)
        assertEquals(in.counterpartyId, Some(alice.id))
        assertEquals(in.balanceAfter.cents, 30L)
        assertEquals(in.id, out.id, "both legs share the transfer id")
      case other => fail(s"expected one transfer on each side, got $other")
  }

  test("transactionsFor fails for an unknown account and is empty for a new one") {
    for
      service <- newService
      alice   <- service.createAccount("Alice")
      unknown <- AccountId.random[IO]
      empty   <- service.transactionsFor(alice.id)
      missing <- service.transactionsFor(unknown)
    yield
      assertEquals(empty, Right(Nil))
      assertEquals(missing, Left(LedgerError.AccountNotFound(unknown)))
  }

  test("concurrent deposits are all applied: the Ref update is atomic") {
    for
      service <- newService
      alice   <- service.createAccount("Alice")
      _       <- List.fill(200)(service.deposit(alice.id, cents(1))).parSequence
      after   <- service.getAccount(alice.id)
      txs     <- service.transactionsFor(alice.id)
    yield
      assertEquals(after.map(_.balance.cents), Right(200L))
      assertEquals(txs.map(_.size), Right(200))
  }

  test("concurrent transfers never overdraw the source account") {
    for
      service <- newService
      alice   <- service.createAccount("Alice")
      bob     <- service.createAccount("Bob")
      _       <- service.deposit(alice.id, cents(100))
      results <- List.fill(50)(service.transfer(alice.id, bob.id, cents(10))).parSequence
      aliceAfter <- service.getAccount(alice.id)
      bobAfter   <- service.getAccount(bob.id)
    yield
      assertEquals(results.count(_.isRight), 10)
      assertEquals(aliceAfter.map(_.balance.cents), Right(0L))
      assertEquals(bobAfter.map(_.balance.cents), Right(100L))
  }

  test("transactions are recorded in order, most recent first") {
    for
      service <- newService
      alice   <- service.createAccount("Alice")
      _       <- service.deposit(alice.id, cents(100))
      _       <- service.deposit(alice.id, cents(50))
      result  <- service.transactionsFor(alice.id)
    yield result match
      case Right(txs) => assertEquals(txs.map(_.amount.cents), List(50L, 100L))
      case Left(err)  => fail(s"expected success, got $err")
  }

  test("deposits and transfers log one line before creating the transaction") {
    val logger = StructuredTestingLogger.impl[IO]()
    for
      ref    <- Ref.of[IO, LedgerState](LedgerState.empty)
      service = LedgerService.inMemory[IO](ref)(using Sync[IO], RecordingLoggerFactory(logger))
      alice  <- service.createAccount("Alice")
      bob    <- service.createAccount("Bob")
      _      <- service.deposit(alice.id, cents(500))
      _      <- service.transfer(alice.id, bob.id, cents(200))
      logged <- logger.logged
    yield assertEquals(
      logged.map(_.message),
      Vector(
        s"Creating deposit transaction on account ${alice.id.asString}",
        s"Creating transfer transaction from account ${alice.id.asString} to ${bob.id.asString}"
      )
    )
  }
