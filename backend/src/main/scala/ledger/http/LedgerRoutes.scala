package ledger.http

import cats.effect.Async
import cats.syntax.all.*
import io.circe.syntax.*
import org.http4s.{EntityDecoder, EntityEncoder, HttpRoutes}
import org.http4s.circe.{jsonEncoderOf, jsonOf}
import org.http4s.dsl.Http4sDsl
import ledger.domain.*
import ledger.service.LedgerService
import JsonCodecs.given

final class LedgerRoutes[F[_]: Async](service: LedgerService[F]) extends Http4sDsl[F]:

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
      UnprocessableEntity(
        JsonCodecs
          .ErrorResponse(
            s"Account ${id.asString} has insufficient funds: balance=${balance.toDouble}, requested=${requested.toDouble}"
          )
          .asJson
      )
    case LedgerError.SameAccountTransfer(id) =>
      UnprocessableEntity(JsonCodecs.ErrorResponse(s"Cannot transfer to the same account: ${id.asString}").asJson)

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
            resp <- Money.fromCents(Math.round(body.amount * 100)) match
                      case Left(err) => UnprocessableEntity(JsonCodecs.ErrorResponse(err).asJson)
                      case Right(amount) =>
                        service.deposit(id, amount).flatMap {
                          case Right(account) => Ok(account)
                          case Left(err)      => errorResponse(err)
                        }
          yield resp

    case req @ POST -> Root / "transfers" =>
      for
        body <- req.as[JsonCodecs.TransferRequest]
        resp <- (AccountId.fromString(body.from), AccountId.fromString(body.to), Money.fromCents(Math.round(body.amount * 100))) match
                  case (Right(from), Right(to), Right(amount)) =>
                    service.transfer(from, to, amount).flatMap {
                      case Right((updatedFrom, updatedTo)) => Ok(JsonCodecs.TransferResponse(updatedFrom, updatedTo))
                      case Left(err)                        => errorResponse(err)
                    }
                  case _ =>
                    BadRequest(JsonCodecs.ErrorResponse("Invalid transfer request").asJson)
      yield resp

    case GET -> Root / "accounts" / rawId / "transactions" =>
      AccountId.fromString(rawId) match
        case Left(err) => BadRequest(JsonCodecs.ErrorResponse(err).asJson)
        case Right(id) =>
          service.transactionsFor(id).flatMap {
            case Right(txs) => Ok(txs)
            case Left(err)  => errorResponse(err)
          }
  }
