package ledger.http

import cats.syntax.all.*
import ledger.domain.AccountId
import ledger.service.{PageSize, TransactionQuery}

/** Turns the query parameters of `GET /transactions/stream` into a [[TransactionQuery]].
  *
  * Pure and independent of http4s: every invalid value is a `Left` with the message the client
  * gets in its 400.
  */
object TransactionStreamParams:

  def parse(
      account: Option[String],
      after: Option[String],
      pageSize: Option[String],
      defaultPageSize: PageSize
  ): Either[String, TransactionQuery] =
    for
      accountId <- account.traverse(AccountId.fromString)
      afterSeq  <- after.traverse { raw =>
                     raw.toLongOption.filter(_ >= 0).toRight(s"after must be a non-negative sequence number, got \"$raw\"")
                   }
      size      <- pageSize.traverse { raw =>
                     raw.toIntOption.toRight(s"pageSize must be a number, got \"$raw\"").flatMap(PageSize.from)
                   }
    yield TransactionQuery(accountId, afterSeq.getOrElse(0L), size.getOrElse(defaultPageSize))
