package ledger.http

import cats.effect.Async
import cats.effect.std.Semaphore
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
  * Owns the slots that cap how many streams run at once: beyond `maxConcurrentStreams` a request gets
  * a 503 with `Retry-After`. A slot is released by the response body's finalizer, so when the stream
  * completes, fails or the client disconnects. (As with http4s' own request-counting middlewares, a
  * server that never evaluated the body wouldn't release it; Ember always does.)
  */
final class TransactionStream[F[_]: Async] private (
    service: LedgerService[F],
    settings: TransactionStreamSettings,
    slots: Semaphore[F]
):

  def defaultPageSize: PageSize = settings.defaultPageSize

  def respond(query: TransactionQuery): F[Response[F]] =
    slots.tryAcquire.ifM(ndjson(query), tooManyStreams)

  // One transaction per line, oldest first, then a StreamEnd line. Each page is encoded and written as
  // soon as it's read, and the next one is only read when the client keeps up (backpressure), so
  // nothing holds the whole ledger in memory. If the client disconnects or the stream fails, http4s
  // interrupts the body stream and its finalizers run: the slot is released, and so would be a storage
  // cursor acquired with Stream.bracket/resource. StreamEnd is only written when the stream completes,
  // so a client can tell a response cut short (after the 200 was sent) from a complete one.
  private def ndjson(query: TransactionQuery): F[Response[F]] =
    val entries: Stream[F, Option[LedgerEntry]] = service.streamTransactions(query).map(Option(_)) ++ Stream.emit(None)
    val body = entries
      .mapAccumulate((0L, query.after)) {
        case ((count, _), Some(entry)) => ((count + 1, entry.seq), entry.asJson.noSpaces)
        case ((count, lastSeq), None)  => ((count, lastSeq), JsonCodecs.StreamEnd(true, count, lastSeq).asJson.noSpaces)
      }
      .map((_, line) => line + "\n")
      .through(fs2.text.utf8.encode)
      .onFinalize(slots.release)
    Response[F](Status.Ok)
      .withEntity(body)
      .putHeaders(`Content-Type`(TransactionStream.NdJson), `Transfer-Encoding`(TransferCoding.chunked))
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
    Semaphore[F](settings.maxConcurrentStreams.toLong).map(slots => new TransactionStream[F](service, settings, slots))
