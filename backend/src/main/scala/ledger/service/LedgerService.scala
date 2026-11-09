package ledger.service

import java.time.Instant
import java.util.UUID
import cats.data.EitherT
import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import org.typelevel.log4cats.LoggerFactory
import ledger.domain.*

/** @param transactions each account's history, newest first, for per-account reads
  * @param ledger every transaction of every account, oldest first, as an append-only log that
  *   `streamTransactions` pages through. It holds the same transactions as `transactions`, indexed
  *   by position instead of by account: a deliberate denormalization, so the whole ledger can be
  *   read in order without merging every account's history. Both are only ever changed together,
  *   in the same atomic `Ref` update.
  */
final case class LedgerState(
    accounts: Map[AccountId, Account],
    transactions: Map[AccountId, List[Transaction]],
    ledger: Vector[LedgerEntry]
)

object LedgerState:
  val empty: LedgerState = LedgerState(Map.empty, Map.empty, Vector.empty)

trait LedgerService[F[_]]:
  def createAccount(owner: String): F[Account]
  def getAccount(id: AccountId): F[Either[LedgerError, Account]]
  def deposit(id: AccountId, amount: Money): F[Either[LedgerError, Account]]
  def transfer(from: AccountId, to: AccountId, amount: Money): F[Either[LedgerError, (Account, Account)]]
  def transactionsFor(id: AccountId): F[Either[LedgerError, List[Transaction]]]

  /** The ledger's entries matching `query`, oldest first, read from storage a page at a time: the
    * whole ledger is never held in memory at once, and the next page is only read when the consumer
    * asks for it.
    */
  def streamTransactions(query: TransactionQuery): Stream[F, LedgerEntry]

  /** The `seq` of the ledger's last entry, 0 while it is empty. */
  def lastSeq: F[Long]

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
                                transactions = state.transactions.updatedWith(id)(txs => Some(tx :: txs.getOrElse(Nil))),
                                ledger = state.ledger :+ LedgerEntry(state.ledger.size + 1L, tx)
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
                            ledger = state.ledger ++ Vector(
                              LedgerEntry(state.ledger.size + 1L, txOut),
                              LedgerEntry(state.ledger.size + 2L, txIn)
                            )
                          )
                          (newState, Right((updatedFrom, updatedTo)))
                    })
        yield result).value

      def transactionsFor(id: AccountId): F[Either[LedgerError, List[Transaction]]] =
        ref.get.map { state =>
          if state.accounts.contains(id) then Right(state.transactions.getOrElse(id, Nil))
          else Left(LedgerError.AccountNotFound(id))
        }

      // Keyset pagination by seq, the in-memory form of
      //   SELECT ... WHERE seq > :after [AND account_id = :account] ORDER BY seq LIMIT :pageSize
      // run once per page: seq n is entry n - 1 of the append-only log, so a page is a slice. Each page
      // is read only when the consumer pulls the previous one (backpressure). With a database, the
      // same Stream would come from that query through a cursor (e.g. Doobie's `query.stream`).
      // The account filter is applied to each page read, so a page may emit fewer entries than it
      // read, or none. The log's length when the stream starts bounds it, so entries recorded while
      // it runs don't keep it going: a reader resumes them with `after`.
      def lastSeq: F[Long] = ref.get.map(_.ledger.size.toLong)

      def streamTransactions(query: TransactionQuery): Stream[F, LedgerEntry] =
        Stream.eval(lastSeq).flatMap { lastSeq =>
          Stream.unfoldChunkEval[F, Long, LedgerEntry](query.after) { after =>
            if after >= lastSeq then Sync[F].pure(Option.empty[(Chunk[LedgerEntry], Long)])
            else
              ref.get.map { state =>
                val page     = state.ledger.slice(after.toInt, math.min(after + query.pageSize.value, lastSeq).toInt)
                val matching = query.account.fold(page)(id => page.filter(_.transaction.accountId == id))
                Option((Chunk.from(matching), after + page.size))
              }
          }
        }
