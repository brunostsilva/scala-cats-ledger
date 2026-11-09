package ledger.service

import cats.effect.MonadCancelThrow
import cats.syntax.all.*
import fs2.Stream
import org.typelevel.log4cats.StructuredLogger
import ledger.domain.*

/** Decorates a [[LedgerService]] with an audit trail: one structured log line per change to the
  * ledger (account created, deposit, transfer) and per rejected change, with the account ids, the
  * amount and the reason it was rejected.
  *
  * Owner names and balances are never logged: the trail records what happened to which account, not
  * who owns it or how much it holds. Reads are not audited. The underlying service stays free of any
  * logging concern, and its results are returned unchanged.
  *
  * Each change and its audit line run as one uncancelable step, so a cancelled request (e.g. the
  * client disconnecting) can't commit a change without recording it. A failing logger never fails
  * the operation: the change is already committed, and turning it into an error would invite a
  * retry that applies it twice.
  */
object AuditedLedgerService:

  /** A dedicated logger name, so the audit trail can be routed or filtered on its own. */
  val LoggerName = "ledger.audit"

  def apply[F[_]: MonadCancelThrow](underlying: LedgerService[F], logger: StructuredLogger[F]): LedgerService[F] =
    new LedgerService[F]:

      private def audited[A](change: F[A])(audit: A => F[Unit]): F[A] =
        MonadCancelThrow[F].uncancelable(_ => change.flatTap(result => audit(result).attempt.void))

      def createAccount(owner: String): F[Account] =
        audited(underlying.createAccount(owner)) { account =>
          logger.info(Map("event" -> "account.created", "account.id" -> account.id.asString))(
            s"Account ${account.id.asString} created"
          )
        }

      def getAccount(id: AccountId): F[Either[LedgerError, Account]] =
        underlying.getAccount(id)

      def deposit(id: AccountId, amount: Money): F[Either[LedgerError, Account]] =
        val context = Map("account.id" -> id.asString, "amount" -> render(amount))
        audited(underlying.deposit(id, amount)) {
          case Right(_) =>
            logger.info(context + ("event" -> "deposit.completed"))(
              s"Deposit of ${render(amount)} into account ${id.asString}"
            )
          case Left(error) =>
            logger.info(context ++ rejection("deposit", error))(
              s"Deposit of ${render(amount)} into account ${id.asString} rejected: ${error.code}"
            )
        }

      def transfer(from: AccountId, to: AccountId, amount: Money): F[Either[LedgerError, (Account, Account)]] =
        val context = Map("from.account.id" -> from.asString, "to.account.id" -> to.asString, "amount" -> render(amount))
        audited(underlying.transfer(from, to, amount)) {
          case Right(_) =>
            logger.info(context + ("event" -> "transfer.completed"))(
              s"Transfer of ${render(amount)} from account ${from.asString} to ${to.asString}"
            )
          case Left(error) =>
            logger.info(context ++ rejection("transfer", error))(
              s"Transfer of ${render(amount)} from account ${from.asString} to ${to.asString} rejected: ${error.code}"
            )
        }

      def transactionsFor(id: AccountId): F[Either[LedgerError, List[Transaction]]] =
        underlying.transactionsFor(id)

      def streamTransactions(query: TransactionQuery): Stream[F, LedgerEntry] =
        underlying.streamTransactions(query)

  private def rejection(operation: String, error: LedgerError): Map[String, String] =
    Map("event" -> s"$operation.rejected", "reason" -> error.code)

  private def render(amount: Money): String = amount.toBigDecimal.bigDecimal.toPlainString
