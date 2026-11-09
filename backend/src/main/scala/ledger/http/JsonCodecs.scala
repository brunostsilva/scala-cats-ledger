package ledger.http

import io.circe.{Codec, Decoder, Encoder, Json}
import io.circe.syntax.*
import ledger.domain.*

/** JSON wire format for the API. Money crosses the HTTP boundary as a decimal amount
  * (e.g. 10.50) while being stored internally as integer cents. Amounts are decoded via
  * `BigDecimal` (not `Double`) to avoid floating-point precision loss and silently saturated
  * out-of-range values.
  */
object JsonCodecs:

  given Codec[Money] =
    Codec.from(Decoder.decodeBigDecimal.emap(Money.fromDecimal), Encoder.encodeBigDecimal.contramap(_.toBigDecimal))

  given Codec[AccountId] =
    Codec.from(Decoder.decodeString.emap(AccountId.fromString), Encoder.encodeString.contramap(_.asString))

  given Codec[TransactionType] = Codec.from(
    Decoder.decodeString.emap {
      case "Deposit"  => Right(TransactionType.Deposit)
      case "Transfer" => Right(TransactionType.Transfer)
      case other      => Left(s"Unknown transaction type: $other")
    },
    Encoder.encodeString.contramap(_.toString)
  )

  // The domain model stays free of JSON concerns (and of the wire format of Money and AccountId
  // defined above), so its codecs are derived here instead of with `derives` on the case classes.
  given Codec.AsObject[Account]     = Codec.AsObject.derived
  given Codec.AsObject[Transaction] = Codec.AsObject.derived

  /** A ledger entry on the wire is its transaction, as in an account's history, plus its `seq`. */
  given Encoder.AsObject[LedgerEntry] =
    Encoder.AsObject.instance(entry => ("seq" -> Json.fromLong(entry.seq)) +: entry.transaction.asJsonObject)

  final case class CreateAccountRequest(owner: String) derives Codec.AsObject

  final case class AmountRequest(amount: BigDecimal) derives Codec.AsObject

  final case class TransferRequest(from: String, to: String, amount: BigDecimal) derives Codec.AsObject

  final case class TransferResponse(from: Account, to: Account) derives Codec.AsObject

  final case class ErrorResponse(message: String) derives Codec.AsObject

  /** Last line of a transaction stream, only written when the stream completes: a response without it
    * was cut short. `lastSeq` is the `after` to resume from (the `after` asked for if nothing matched).
    */
  final case class StreamEnd(end: Boolean, count: Long, lastSeq: Long) derives Codec.AsObject
