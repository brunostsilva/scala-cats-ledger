package ledger.service

import java.time.Instant
import java.util.UUID
import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import ledger.domain.*

final case class LedgerState(
    accounts: Map[AccountId, Account],
    transactions: Map[AccountId, List[Transaction]]
)

object LedgerState:
  val empty: LedgerState = LedgerState(Map.empty, Map.empty)

trait LedgerService[F[_]]:
  def createAccount(owner: String): F[Account]
  def getAccount(id: AccountId): F[Either[LedgerError, Account]]
  def deposit(id: AccountId, amount: Money): F[Either[LedgerError, Account]]
  def transfer(from: AccountId, to: AccountId, amount: Money): F[Either[LedgerError, (Account, Account)]]
  def transactionsFor(id: AccountId): F[Either[LedgerError, List[Transaction]]]

object LedgerService:

  /** In-memory implementation backed by a single `Ref`, so every mutation is an atomic,
    * race-free compare-and-set over the whole ledger state.
    */
  def inMemory[F[_]: Sync](ref: Ref[F, LedgerState]): LedgerService[F] =
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
          txId   <- Sync[F].delay(UUID.randomUUID())
          now    <- Sync[F].delay(Instant.now())
          result <- ref.modify { state =>
                      state.accounts.get(id) match
                        case None => (state, Left(LedgerError.AccountNotFound(id)))
                        case Some(acc) =>
                          val updated = acc.copy(balance = acc.balance + amount)
                          val tx = Transaction(txId, id, None, TransactionType.Deposit, amount, updated.balance, now)
                          val newState = state.copy(
                            accounts = state.accounts.updated(id, updated),
                            transactions = state.transactions.updatedWith(id)(txs => Some(tx :: txs.getOrElse(Nil)))
                          )
                          (newState, Right(updated))
                    }
        yield result

      def transfer(from: AccountId, to: AccountId, amount: Money): F[Either[LedgerError, (Account, Account)]] =
        if from == to then Sync[F].pure(Left(LedgerError.SameAccountTransfer(from)))
        else
          for
            txId   <- Sync[F].delay(UUID.randomUUID())
            now    <- Sync[F].delay(Instant.now())
            result <- ref.modify { state =>
                        val attempt =
                          for
                            fromAcc        <- state.accounts.get(from).toRight(LedgerError.AccountNotFound(from))
                            toAcc          <- state.accounts.get(to).toRight(LedgerError.AccountNotFound(to))
                            newFromBalance <- (fromAcc.balance - amount).left.map(_ =>
                                                LedgerError.InsufficientFunds(from, fromAcc.balance, amount)
                                              )
                          yield (fromAcc, toAcc, newFromBalance)

                        attempt match
                          case Left(err) => (state, Left(err))
                          case Right((fromAcc, toAcc, newFromBalance)) =>
                            val updatedFrom = fromAcc.copy(balance = newFromBalance)
                            val updatedTo   = toAcc.copy(balance = toAcc.balance + amount)
                            val txOut = Transaction(txId, from, Some(to), TransactionType.Transfer, amount, updatedFrom.balance, now)
                            val txIn  = Transaction(txId, to, Some(from), TransactionType.Transfer, amount, updatedTo.balance, now)
                            val newState = state.copy(
                              accounts = state.accounts.updated(from, updatedFrom).updated(to, updatedTo),
                              transactions = state.transactions
                                .updatedWith(from)(txs => Some(txOut :: txs.getOrElse(Nil)))
                                .updatedWith(to)(txs => Some(txIn :: txs.getOrElse(Nil)))
                            )
                            (newState, Right((updatedFrom, updatedTo)))
                      }
          yield result

      def transactionsFor(id: AccountId): F[Either[LedgerError, List[Transaction]]] =
        ref.get.map { state =>
          if state.accounts.contains(id) then Right(state.transactions.getOrElse(id, Nil))
          else Left(LedgerError.AccountNotFound(id))
        }
