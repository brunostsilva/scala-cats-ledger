package ledger.service

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import munit.CatsEffectSuite
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory
import ledger.domain.*

/** `LedgerService.streamTransfers` over the in-memory store: order, paging and its snapshot bound. */
class TransferStreamSpec extends CatsEffectSuite:

  private given LoggerFactory[IO] = NoOpFactory[IO]

  private def cents(c: Long): Money = Money.fromCents(c).toOption.get

  private final case class Fixture(service: LedgerService[IO], alice: Account, bob: Account)

  /** Alice deposits 100.00, then sends Bob `count` transfers of 1.00, 2.00, 3.00... */
  private def fixture(count: Int): IO[Fixture] =
    for
      ref     <- Ref.of[IO, LedgerState](LedgerState.empty)
      service  = LedgerService.inMemory[IO](ref)
      alice   <- service.createAccount("Alice")
      bob     <- service.createAccount("Bob")
      _       <- service.deposit(alice.id, cents(10000))
      _       <- (1 to count).toList.traverse_(i => service.transfer(alice.id, bob.id, cents(i * 100L)))
    yield Fixture(service, alice, bob)

  test("emits every transfer once, oldest first, across page boundaries, and no deposits") {
    for
      fx        <- fixture(7)
      transfers <- fx.service.streamTransfers(pageSize = 3).compile.toList // pages of 3, 3 and 1
    yield
      assertEquals(transfers.map(_.amount), (1 to 7).toList.map(i => cents(i * 100L)))
      assert(transfers.forall(t => t.from == fx.alice.id && t.to == fx.bob.id), transfers)
      assertEquals(transfers.map(_.id).distinct.size, 7)
  }

  test("transfer ids match the transfer legs in the accounts' histories") {
    for
      fx        <- fixture(4)
      transfers <- fx.service.streamTransfers(pageSize = 2).compile.toList
      history   <- fx.service.transactionsFor(fx.bob.id)
    yield assertEquals(transfers.map(_.id).toSet, history.toOption.get.map(_.id).toSet)
  }

  test("an empty ledger emits nothing") {
    for
      fx        <- fixture(0)
      transfers <- fx.service.streamTransfers(pageSize = 10).compile.toList
    yield assertEquals(transfers, Nil)
  }

  test("a stream only covers the transfers that existed when it started") {
    for
      fx       <- fixture(3)
      // Every element read triggers a new transfer: an unbounded stream would never end.
      streamed <- fx.service
                    .streamTransfers(pageSize = 1)
                    .evalTap(_ => fx.service.transfer(fx.alice.id, fx.bob.id, cents(1)).void)
                    .compile
                    .count
      later    <- fx.service.streamTransfers(pageSize = 1).compile.count
    yield
      assertEquals(streamed, 3L)
      assertEquals(later, 6L)
  }

  test("a non-positive page size is rejected") {
    for
      fx     <- fixture(1)
      result <- fx.service.streamTransfers(pageSize = 0).compile.drain.attempt
    yield assert(result.left.exists(_.isInstanceOf[IllegalArgumentException]), result)
  }
