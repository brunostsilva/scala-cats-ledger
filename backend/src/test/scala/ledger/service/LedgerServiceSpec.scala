package ledger.service

import cats.effect.{IO, Ref}
import ledger.domain.*
import munit.CatsEffectSuite

class LedgerServiceSpec extends CatsEffectSuite:

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
