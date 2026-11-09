package ledger.http

import cats.syntax.all.*
import ledger.service.PageSize

/** Server-side settings of `GET /transactions/stream`.
  *
  * @param defaultPageSize entries read from storage per page when the request doesn't ask for a size
  * @param maxConcurrentStreams streams served at once; further requests get a 503 until one ends
  */
final case class TransactionStreamSettings(defaultPageSize: PageSize, maxConcurrentStreams: Int)

object TransactionStreamSettings:

  val Default: TransactionStreamSettings = TransactionStreamSettings(PageSize.Default, maxConcurrentStreams = 8)

  /** Reads `TRANSACTION_STREAM_PAGE_SIZE` and `TRANSACTION_STREAM_MAX_CONCURRENT`, falling back to [[Default]]. */
  def fromEnv(env: Map[String, String]): Either[String, TransactionStreamSettings] =
    for
      pageSize <- env.get("TRANSACTION_STREAM_PAGE_SIZE").traverse { raw =>
                    raw.toIntOption
                      .toRight(s"TRANSACTION_STREAM_PAGE_SIZE must be a number, got \"$raw\"")
                      .flatMap(n => PageSize.from(n).left.map(e => s"TRANSACTION_STREAM_PAGE_SIZE: $e"))
                  }
      maxStreams <- env.get("TRANSACTION_STREAM_MAX_CONCURRENT").traverse { raw =>
                      raw.toIntOption
                        .filter(_ >= 1)
                        .toRight(s"TRANSACTION_STREAM_MAX_CONCURRENT must be a positive number, got \"$raw\"")
                    }
    yield TransactionStreamSettings(
      pageSize.getOrElse(Default.defaultPageSize),
      maxStreams.getOrElse(Default.maxConcurrentStreams)
    )
