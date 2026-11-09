package ledger.http

import cats.effect.Async
import cats.effect.std.Semaphore
import cats.syntax.all.*
import fs2.Stream
import io.circe.syntax.*
import org.http4s.{EntityDecoder, EntityEncoder, HttpRoutes, MediaType, Response, TransferCoding}
import org.http4s.circe.{jsonEncoder, jsonEncoderOf, jsonOf}
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.{`Content-Type`, `Retry-After`, `Transfer-Encoding`}
import ledger.domain.*
import ledger.service.{LedgerService, PageSize, TransactionQuery}
import JsonCodecs.given

/** The HTTP API. Built with [[LedgerRoutes.make]], which also creates the slots that cap how many
  * transaction streams run at once.
  */
final class LedgerRoutes[F[_]: Async] private (
    service: LedgerService[F],
    settings: TransactionStreamSettings,
    streamSlots: Semaphore[F]
) extends Http4sDsl[F]:

  private given EntityDecoder[F, JsonCodecs.CreateAccountRequest] = jsonOf
  private given EntityDecoder[F, JsonCodecs.AmountRequest]        = jsonOf
  private given EntityDecoder[F, JsonCodecs.TransferRequest]      = jsonOf
  private given EntityEncoder[F, Account]                         = jsonEncoderOf
  private given EntityEncoder[F, List[Transaction]]               = jsonEncoderOf
  private given EntityEncoder[F, JsonCodecs.TransferResponse]     = jsonEncoderOf

  private def errorResponse(err: LedgerError): F[org.http4s.Response[F]] = err match
    case LedgerError.AccountNotFound(id) =>
      NotFound(JsonCodecs.ErrorResponse(s"Account not found: ${id.asString}").asJson)
    case LedgerError.InsufficientFunds(id, balance, requested) =>
      UnprocessableContent(
        JsonCodecs
          .ErrorResponse(
            s"Account ${id.asString} has insufficient funds: balance=${balance.toBigDecimal}, requested=${requested.toBigDecimal}"
          )
          .asJson
      )
    case LedgerError.SameAccountTransfer(id) =>
      UnprocessableContent(JsonCodecs.ErrorResponse(s"Cannot transfer to the same account: ${id.asString}").asJson)
    case LedgerError.InvalidAmount(message) =>
      UnprocessableContent(JsonCodecs.ErrorResponse(message).asJson)

  private object AccountParam  extends OptionalQueryParamDecoderMatcher[String]("account")
  private object AfterParam    extends OptionalQueryParamDecoderMatcher[String]("after")
  private object PageSizeParam extends OptionalQueryParamDecoderMatcher[String]("pageSize")

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "accounts" =>
      for
        body    <- req.as[JsonCodecs.CreateAccountRequest]
        account <- service.createAccount(body.owner)
        resp    <- Created(account)
      yield resp

    case GET -> Root / "accounts" / rawId =>
      AccountId.fromString(rawId) match
        case Left(err) => BadRequest(JsonCodecs.ErrorResponse(err).asJson)
        case Right(id) =>
          service.getAccount(id).flatMap {
            case Right(account) => Ok(account)
            case Left(err)      => errorResponse(err)
          }

    case req @ POST -> Root / "accounts" / rawId / "deposit" =>
      AccountId.fromString(rawId) match
        case Left(err) => BadRequest(JsonCodecs.ErrorResponse(err).asJson)
        case Right(id) =>
          for
            body <- req.as[JsonCodecs.AmountRequest]
            resp <- Money.fromDecimal(body.amount) match
                      case Left(err) => UnprocessableContent(JsonCodecs.ErrorResponse(err).asJson)
                      case Right(amount) =>
                        service.deposit(id, amount).flatMap {
                          case Right(account) => Ok(account)
                          case Left(err)      => errorResponse(err)
                        }
          yield resp

    case req @ POST -> Root / "transfers" =>
      for
        body <- req.as[JsonCodecs.TransferRequest]
        resp <- (AccountId.fromString(body.from), AccountId.fromString(body.to), Money.fromDecimal(body.amount)) match
                  case (Right(from), Right(to), Right(amount)) =>
                    service.transfer(from, to, amount).flatMap {
                      case Right((updatedFrom, updatedTo)) => Ok(JsonCodecs.TransferResponse(updatedFrom, updatedTo))
                      case Left(err)                        => errorResponse(err)
                    }
                  case _ =>
                    BadRequest(JsonCodecs.ErrorResponse("Invalid transfer request").asJson)
      yield resp

    // The ledger as NDJSON: one transaction per line, oldest first, then a StreamEnd line. Everything
    // that can be refused (bad parameters, unknown account, too many streams) is checked before the
    // 200, since a stream can't change its status once it has started.
    case GET -> Root / "transactions" / "stream" :? AccountParam(account) +& AfterParam(after) +& PageSizeParam(pageSize) =>
      streamQuery(account, after, pageSize) match
        case Left(message) => BadRequest(JsonCodecs.ErrorResponse(message).asJson)
        case Right(query) =>
          query.account.traverse(service.getAccount).map(_.sequence).flatMap {
            case Left(err) => errorResponse(err)
            case Right(_)  => streamSlots.tryAcquire.ifM(streamResponse(query), tooManyStreams)
          }

    case GET -> Root / "accounts" / rawId / "transactions" =>
      AccountId.fromString(rawId) match
        case Left(err) => BadRequest(JsonCodecs.ErrorResponse(err).asJson)
        case Right(id) =>
          service.transactionsFor(id).flatMap {
            case Right(txs) => Ok(txs)
            case Left(err)  => errorResponse(err)
          }
  }

  private def streamQuery(
      account: Option[String],
      after: Option[String],
      pageSize: Option[String]
  ): Either[String, TransactionQuery] =
    for
      accountId <- account.traverse(AccountId.fromString)
      afterSeq  <- after.traverse { raw =>
                     raw.toLongOption.filter(_ >= 0).toRight(s"after must be a non-negative sequence number, got \"$raw\"")
                   }
      size      <- pageSize.traverse { raw =>
                     raw.toIntOption.toRight(s"pageSize must be a number, got \"$raw\"").flatMap(PageSize.from)
                   }
    yield TransactionQuery(accountId, afterSeq.getOrElse(0L), size.getOrElse(settings.defaultPageSize))

  // Each page is encoded and written as soon as it's read, and the next one is only read when the
  // client keeps up (backpressure), so nothing holds the whole ledger in memory. If the client
  // disconnects or the stream fails, http4s interrupts the body stream and its finalizers run: the
  // stream slot is released, and so would be a storage cursor acquired with Stream.bracket/resource.
  // The StreamEnd line is only written when the stream completes, so a client can tell a response
  // cut short by a failure (after the 200 was sent) from a complete one, and resume with `after`.
  private def streamResponse(query: TransactionQuery): F[Response[F]] =
    val entries: Stream[F, Option[LedgerEntry]] = service.streamTransactions(query).map(Option(_)) ++ Stream.emit(None)
    val ndjson = entries
      .mapAccumulate((0L, query.after)) {
        case ((count, _), Some(entry)) => ((count + 1, entry.seq), entry.asJson.noSpaces)
        case ((count, lastSeq), None)  => ((count, lastSeq), JsonCodecs.StreamEnd(true, count, lastSeq).asJson.noSpaces)
      }
      .map((_, line) => line + "\n")
      .through(fs2.text.utf8.encode)
      .onFinalize(streamSlots.release)
    Ok(ndjson, `Content-Type`(LedgerRoutes.NdJson), `Transfer-Encoding`(TransferCoding.chunked))

  private def tooManyStreams: F[Response[F]] =
    ServiceUnavailable(
      JsonCodecs
        .ErrorResponse(s"Too many transaction streams in progress (at most ${settings.maxConcurrentStreams}), retry shortly")
        .asJson,
      `Retry-After`.unsafeFromLong(1)
    )

object LedgerRoutes:

  val NdJson: MediaType = MediaType.unsafeParse("application/x-ndjson")

  def make[F[_]: Async](
      service: LedgerService[F],
      settings: TransactionStreamSettings = TransactionStreamSettings.Default
  ): F[LedgerRoutes[F]] =
    Semaphore[F](settings.maxConcurrentStreams.toLong).map(slots => new LedgerRoutes[F](service, settings, slots))
