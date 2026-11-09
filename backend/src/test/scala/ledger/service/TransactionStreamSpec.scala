package ledger.service

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import munit.CatsEffectSuite
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory
import ledger.domain.*

/** `LedgerService.streamTransactions` over the in-memory store: ledger order, paging, filters,
  * resumption, snapshot bound, and what does or doesn't reach the ledger.
  */
class TransactionStreamSpec extends CatsEffectSuite:

  private given LoggerFactory[IO] = NoOpFactory[IO]

  private def cents(c: Long): Money = Money.fromCents(c).toOption.get
  private def page(n: Int): PageSize = PageSize.from(n).toOption.get

  private final case class Fixture(service: LedgerService[IO], alice: Account, bob: Account)

  /** Alice deposits 100.00, then sends Bob `count` transfers of 1.00, 2.00...: 1 + 2 * count entries
    * (seq 1 is the deposit, then each transfer is Alice's leg followed by Bob's).
    */
  private def fixture(count: Int): IO[Fixture] =
    for
      ref     <- Ref.of[IO, LedgerState](LedgerState.empty)
      service  = LedgerService.inMemory[IO](ref)
      alice   <- service.createAccount("Alice")
      bob     <- service.createAccount("Bob")
      _       <- service.deposit(alice.id, cents(10000))
      _       <- (1 to count).toList.traverse_(i => service.transfer(alice.id, bob.id, cents(i * 100L)))
    yield Fixture(service, alice, bob)

  private def stream(fx: Fixture, query: TransactionQuery): IO[List[LedgerEntry]] =
    fx.service.streamTransactions(query).compile.toList

  test("streams every transaction once, in ledger order with consecutive seqs, across page boundaries") {
    for
      fx      <- fixture(3)
      entries <- stream(fx, TransactionQuery(pageSize = page(3))) // pages of 3, 3 and 1
    yield
      assertEquals(entries.map(_.seq), (1L to 7L).toList)
      assertEquals(entries.head.transaction.txType, TransactionType.Deposit)
      val legs = entries.tail.grouped(2).toList
      legs.foreach {
        case List(out, in) =>
          assertEquals(out.transaction.id, in.transaction.id, "both legs of a transfer share its id")
          assertEquals((out.transaction.accountId, in.transaction.accountId), (fx.alice.id, fx.bob.id))
        case other => fail(s"expected two legs per transfer, got $other")
      }
      assertEquals(legs.map(_.head.transaction.amount), List(1L, 2L, 3L).map(i => cents(i * 100)))
  }

  test("with an account, streams exactly that account's history, oldest first, skipping pages without it") {
    for
      fx    <- fixture(3)
      // Pages of 2: the account filter leaves some pages (e.g. seq 1-2 for Bob) empty.
      alice <- stream(fx, TransactionQuery(account = Some(fx.alice.id), pageSize = page(2)))
      bob   <- stream(fx, TransactionQuery(account = Some(fx.bob.id), pageSize = page(2)))
      aliceHistory <- fx.service.transactionsFor(fx.alice.id)
      bobHistory   <- fx.service.transactionsFor(fx.bob.id)
    yield
      assertEquals(alice.map(_.transaction), aliceHistory.toOption.get.reverse)
      assertEquals(bob.map(_.transaction), bobHistory.toOption.get.reverse)
      assertEquals(bob.map(_.seq), List(3L, 5L, 7L))
  }

  test("after resumes right after the given seq") {
    for
      fx      <- fixture(3)
      resumed <- stream(fx, TransactionQuery(after = 4, pageSize = page(2)))
      beyond  <- stream(fx, TransactionQuery(after = 7))
    yield
      assertEquals(resumed.map(_.seq), List(5L, 6L, 7L))
      assertEquals(beyond, Nil)
  }

  test("an empty ledger emits nothing") {
    for
      ref     <- Ref.of[IO, LedgerState](LedgerState.empty)
      entries <- LedgerService.inMemory[IO](ref).streamTransactions(TransactionQuery()).compile.toList
    yield assertEquals(entries, Nil)
  }

  test("a stream only covers the entries recorded before it started") {
    for
      fx       <- fixture(1) // 3 entries
      // Every entry read records a new deposit: an unbounded stream would never end.
      streamed <- fx.service
                    .streamTransactions(TransactionQuery(pageSize = page(1)))
                    .evalTap(_ => fx.service.deposit(fx.alice.id, cents(1)).void)
                    .compile
                    .count
      later    <- fx.service.streamTransactions(TransactionQuery()).compile.count
    yield
      assertEquals(streamed, 3L)
      assertEquals(later, 6L)
  }

  test("rejected operations never reach the ledger") {
    for
      fx      <- fixture(0) // only Alice's deposit
      unknown <- AccountId.random[IO]
      results <- List(
                   fx.service.transfer(fx.bob.id, fx.alice.id, cents(100)).map(_.void),  // insufficient funds
                   fx.service.transfer(fx.alice.id, unknown, cents(100)).map(_.void),    // unknown account
                   fx.service.transfer(fx.alice.id, fx.alice.id, cents(100)).map(_.void), // same account
                   fx.service.deposit(unknown, cents(100)).map(_.void)                    // unknown account
                 ).sequence
      entries <- stream(fx, TransactionQuery())
    yield
      assert(results.forall(_.isLeft), results)
      assertEquals(entries.map(e => (e.seq, e.transaction.txType)), List((1L, TransactionType.Deposit)))
  }

  test("concurrent transfers: exactly the ones that succeeded, two entries each, with no gaps in seq") {
    for
      fx      <- fixture(0) // Alice has 100.00: only 10 of the 50 transfers of 10.00 can succeed
      results <- List.fill(50)(fx.service.transfer(fx.alice.id, fx.bob.id, cents(1000))).parSequence
      entries <- stream(fx, TransactionQuery(pageSize = page(7)))
      bob     <- fx.service.getAccount(fx.bob.id)
    yield
      val succeeded = results.count(_.isRight)
      assertEquals(succeeded, 10)
      assertEquals(entries.map(_.seq), (1L to (1L + 2 * succeeded)).toList)
      val transferLegs = entries.filter(_.transaction.txType == TransactionType.Transfer)
      assertEquals(transferLegs.groupBy(_.transaction.id).values.map(_.size).toList, List.fill(succeeded)(2))
      val bobReceived = transferLegs.filter(_.transaction.accountId == fx.bob.id).map(_.transaction.amount.cents).sum
      assertEquals(bobReceived, bob.toOption.get.balance.cents)
  }
