package ledger.service

import java.time.Instant
import java.util.UUID
import cats.data.EitherT
import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import org.typelevel.log4cats.LoggerFactory
import ledger.domain.*

/** @param transfers append-only log of completed transfers, oldest first: the "table" that
  *   `streamTransfers` pages through.
  */
final case class LedgerState(
    accounts: Map[AccountId, Account],
    transactions: Map[AccountId, List[Transaction]],
    transfers: Vector[Transfer]
)

object LedgerState:
  val empty: LedgerState = LedgerState(Map.empty, Map.empty, Vector.empty)

trait LedgerService[F[_]]:
  def createAccount(owner: String): F[Account]
  def getAccount(id: AccountId): F[Either[LedgerError, Account]]
  def deposit(id: AccountId, amount: Money): F[Either[LedgerError, Account]]
  def transfer(from: AccountId, to: AccountId, amount: Money): F[Either[LedgerError, (Account, Account)]]
  def transactionsFor(id: AccountId): F[Either[LedgerError, List[Transaction]]]

  /** Every completed transfer, oldest first, read from storage `pageSize` at a time: the whole log
    * is never held in memory at once, and the next page is only read when the consumer asks for it.
    */
  def streamTransfers(pageSize: Int): Stream[F, Transfer]

object LedgerService:

  val LoggerName = "ledger.service"

  /** In-memory implementation backed by a single `Ref`, so every mutation is an atomic,
    * race-free compare-and-set over the whole ledger state.
    */
  def inMemory[F[_]: Sync: LoggerFactory](ref: Ref[F, LedgerState]): LedgerService[F] =
    val logger = LoggerFactory[F].getLoggerFromName(LoggerName)

    new LedgerService[F]:

      def createAccount(owner: String): F[Account] =
        for
          id     <- AccountId.random[F]
          account = Account(id, owner, Money.zero)
          _      <- ref.update(s => s.copy(accounts = s.accounts.updated(id, account)))
        yield account

      def getAccount(id: AccountId): F[Either[LedgerError, Account]] =
        ref.get.map(_.accounts.get(id).toRight(LedgerError.AccountNotFound(id)))

      def deposit(id: AccountId, amount: Money): F[Either[LedgerError, Account]] =
        for
          _      <- logger.info(s"Creating deposit transaction on account ${id.asString}")
          txId   <- Sync[F].delay(UUID.randomUUID())
          now    <- Sync[F].delay(Instant.now())
          result <- ref.modify { state =>
                      state.accounts.get(id) match
                        case None => (state, Left(LedgerError.AccountNotFound(id)))
                        case Some(acc) =>
                          acc.balance + amount match
                            case Left(msg) => (state, Left(LedgerError.InvalidAmount(msg)))
                            case Right(newBalance) =>
                              val updated = acc.copy(balance = newBalance)
                              val tx = Transaction(txId, id, None, TransactionType.Deposit, amount, updated.balance, now)
                              val newState = state.copy(
                                accounts = state.accounts.updated(id, updated),
                                transactions = state.transactions.updatedWith(id)(txs => Some(tx :: txs.getOrElse(Nil)))
                              )
                              (newState, Right(updated))
                    }
        yield result

      def transfer(from: AccountId, to: AccountId, amount: Money): F[Either[LedgerError, (Account, Account)]] =
        (for
          _      <- EitherT.cond[F](from != to, (), LedgerError.SameAccountTransfer(from))
          _      <- EitherT.right[LedgerError](logger.info(s"Creating transfer transaction from account ${from.asString} to ${to.asString}"))
          txId   <- EitherT.right[LedgerError](Sync[F].delay(UUID.randomUUID()))
          now    <- EitherT.right[LedgerError](Sync[F].delay(Instant.now()))
          result <- EitherT(ref.modify[Either[LedgerError, (Account, Account)]] { state =>
                      val attempt =
                        for
                          fromAcc        <- state.accounts.get(from).toRight(LedgerError.AccountNotFound(from))
                          toAcc          <- state.accounts.get(to).toRight(LedgerError.AccountNotFound(to))
                          newFromBalance <- (fromAcc.balance - amount).left.map(_ =>
                                              LedgerError.InsufficientFunds(from, fromAcc.balance, amount)
                                            )
                          newToBalance   <- (toAcc.balance + amount).left.map(LedgerError.InvalidAmount.apply)
                        yield (fromAcc, toAcc, newFromBalance, newToBalance)

                      attempt match
                        case Left(err) => (state, Left(err))
                        case Right((fromAcc, toAcc, newFromBalance, newToBalance)) =>
                          val updatedFrom = fromAcc.copy(balance = newFromBalance)
                          val updatedTo   = toAcc.copy(balance = newToBalance)
                          val txOut = Transaction(txId, from, Some(to), TransactionType.Transfer, amount, updatedFrom.balance, now)
                          val txIn  = Transaction(txId, to, Some(from), TransactionType.Transfer, amount, updatedTo.balance, now)
                          val newState = state.copy(
                            accounts = state.accounts.updated(from, updatedFrom).updated(to, updatedTo),
                            transactions = state.transactions
                              .updatedWith(from)(txs => Some(txOut :: txs.getOrElse(Nil)))
                              .updatedWith(to)(txs => Some(txIn :: txs.getOrElse(Nil))),
                            transfers = state.transfers :+ Transfer(txId, from, to, amount, now)
                          )
                          (newState, Right((updatedFrom, updatedTo)))
                    })
        yield result).value

      def transactionsFor(id: AccountId): F[Either[LedgerError, List[Transaction]]] =
        ref.get.map { state =>
          if state.accounts.contains(id) then Right(state.transactions.getOrElse(id, Nil))
          else Left(LedgerError.AccountNotFound(id))
        }

      // Keyset-style pagination over the append-only log, the in-memory counterpart of a database
      // cursor (with Doobie this would be `query.stream` with a fetch size). Each page is a fresh read
      // of `pageSize` entries, emitted as one chunk; the next one is only read on demand (backpressure).
      // The log's length when the stream starts bounds it, so transfers made while it runs don't
      // keep it going forever.
      def streamTransfers(pageSize: Int): Stream[F, Transfer] =
        if pageSize <= 0 then Stream.raiseError[F](new IllegalArgumentException(s"pageSize must be positive: $pageSize"))
        else
          Stream.eval(ref.get.map(_.transfers.size)).flatMap { end =>
            Stream.unfoldChunkEval[F, Int, Transfer](0) { offset =>
              if offset >= end then Sync[F].pure(Option.empty[(Chunk[Transfer], Int)])
              else
                ref.get.map { state =>
                  val page = state.transfers.slice(offset, math.min(offset + pageSize, end))
                  Option((Chunk.from(page), offset + page.size))
                }
            }
          }
