package ledger.http

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import ledger.domain.*

/** JSON wire format for the API. Money crosses the HTTP boundary as a decimal amount
  * (e.g. 10.50) while being stored internally as integer cents.
  */
object JsonCodecs:

  given Encoder[Money] = Encoder.encodeDouble.contramap(_.toDouble)
  given Decoder[Money] = Decoder.decodeDouble.emap(d => Money.fromCents(Math.round(d * 100)))

  given Encoder[AccountId] = Encoder.encodeString.contramap(_.asString)
  given Decoder[AccountId] = Decoder.decodeString.emap(AccountId.fromString)

  given Encoder[TransactionType] = Encoder.encodeString.contramap(_.toString)
  given Decoder[TransactionType] = Decoder.decodeString.emap {
    case "Deposit"  => Right(TransactionType.Deposit)
    case "Transfer" => Right(TransactionType.Transfer)
    case other      => Left(s"Unknown transaction type: $other")
  }

  given Encoder[Account] = deriveEncoder
  given Decoder[Account] = deriveDecoder

  given Encoder[Transaction] = deriveEncoder
  given Decoder[Transaction] = deriveDecoder

  final case class CreateAccountRequest(owner: String)
  given Encoder[CreateAccountRequest] = deriveEncoder
  given Decoder[CreateAccountRequest] = deriveDecoder

  final case class AmountRequest(amount: Double)
  given Encoder[AmountRequest] = deriveEncoder
  given Decoder[AmountRequest] = deriveDecoder

  final case class TransferRequest(from: String, to: String, amount: Double)
  given Encoder[TransferRequest] = deriveEncoder
  given Decoder[TransferRequest] = deriveDecoder

  final case class TransferResponse(from: Account, to: Account)
  given Encoder[TransferResponse] = deriveEncoder
  given Decoder[TransferResponse] = deriveDecoder

  final case class ErrorResponse(message: String)
  given Encoder[ErrorResponse] = deriveEncoder
  given Decoder[ErrorResponse] = deriveDecoder
