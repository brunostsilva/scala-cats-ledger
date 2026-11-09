package ledger.http

import cats.effect.{Async, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.syntax.*
import org.http4s.{MediaType, Response, Status, TransferCoding}
import org.http4s.circe.jsonEncoder
import org.http4s.headers.{`Content-Type`, `Retry-After`, `Transfer-Encoding`}
import ledger.domain.LedgerEntry
import ledger.service.{LedgerService, PageSize, TransactionQuery}
import JsonCodecs.given

/** The response of `GET /transactions/stream`, for a query already validated by the route.
  *
  * Caps how many streams run at once: when `maxConcurrentStreams` are running, a request gets a 503
  * with `Retry-After`. Only streams that actually started are counted: the count goes up when the
  * response body starts and down in its finalizer (completion, failure or client disconnect).
  * Reserving a slot before the 200 instead would leak it whenever the body never starts, e.g. when
  * Ember fails to write the headers to a client that already reset the connection, and enough leaks
  * would lock the endpoint. The trade-off: requests arriving at the same moment can all pass the
  * check before their bodies start, so a burst can briefly run a few streams over the limit.
  */
final class TransactionStream[F[_]: Async] private (
    service: LedgerService[F],
    settings: TransactionStreamSettings,
    running: Ref[F, Int]
):

  def defaultPageSize: PageSize = settings.defaultPageSize

  def respond(query: TransactionQuery): F[Response[F]] =
    service.lastSeq.flatMap { lastSeq =>
      // An `after` past the end can't be a position this ledger ever gave out: usually a cursor from
      // before a restart (seq starts again at 1 with this in-memory ledger). Streaming nothing and
      // echoing it back would make the client silently skip every new entry, so it's refused.
      if query.after > lastSeq then afterBeyondEnd(query.after, lastSeq)
      else running.get.flatMap(count => if count < settings.maxConcurrentStreams then ndjson(query) else tooManyStreams)
    }

  // One transaction per line, oldest first, then a StreamEnd line. Each page is encoded and written as
  // soon as it's read, and the next one is only read when the client keeps up (backpressure), so
  // nothing holds the whole ledger in memory. If the client disconnects or the stream fails, http4s
  // interrupts the body stream and its finalizers run: the stream stops counting against the limit,
  // and a storage cursor acquired with Stream.bracket/resource would be released. StreamEnd is only
  // written when the stream completes, so a client can tell a response cut short (after the 200 was
  // sent) from a complete one.
  private def ndjson(query: TransactionQuery): F[Response[F]] =
    val entries: Stream[F, Option[LedgerEntry]] = service.streamTransactions(query).map(Option(_)) ++ Stream.emit(None)
    val lines = entries
      .mapAccumulate((0L, query.after)) {
        case ((count, _), Some(entry)) => ((count + 1, entry.seq), entry.asJson.noSpaces)
        case ((count, lastSeq), None)  => ((count, lastSeq), JsonCodecs.StreamEnd(true, count, lastSeq).asJson.noSpaces)
      }
      .map((_, line) => line + "\n")
      .through(fs2.text.utf8.encode)
    val body = Stream.bracket(running.update(_ + 1))(_ => running.update(_ - 1)) >> lines
    Response[F](Status.Ok)
      .withEntity(body)
      .putHeaders(`Content-Type`(TransactionStream.NdJson), `Transfer-Encoding`(TransferCoding.chunked))
      .pure[F]

  private def afterBeyondEnd(after: Long, lastSeq: Long): F[Response[F]] =
    Response[F](Status.Conflict)
      .withEntity(
        JsonCodecs
          .ErrorResponse(
            s"after $after is beyond the end of the ledger (lastSeq $lastSeq): the ledger was reset or the " +
              "cursor is wrong; restart from after=0"
          )
          .asJson
      )
      .pure[F]

  private def tooManyStreams: F[Response[F]] =
    Response[F](Status.ServiceUnavailable)
      .withEntity(
        JsonCodecs
          .ErrorResponse(s"Too many transaction streams in progress (at most ${settings.maxConcurrentStreams}), retry shortly")
          .asJson
      )
      .putHeaders(`Retry-After`.unsafeFromLong(1))
      .pure[F]

object TransactionStream:

  val NdJson: MediaType = MediaType.unsafeParse("application/x-ndjson")

  def make[F[_]: Async](
      service: LedgerService[F],
      settings: TransactionStreamSettings = TransactionStreamSettings.Default
  ): F[TransactionStream[F]] =
    Ref.of[F, Int](0).map(running => new TransactionStream[F](service, settings, running))
