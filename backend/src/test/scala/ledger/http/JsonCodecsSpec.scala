package ledger.http

import java.time.Instant
import java.util.UUID
import io.circe.Json
import io.circe.syntax.*
import ledger.domain.*
import munit.FunSuite
import JsonCodecs.given

class JsonCodecsSpec extends FunSuite:

  private def cents(c: Long): Money = Money.fromCents(c).toOption.get
  private def id(raw: String): AccountId = AccountId.fromString(raw).toOption.get

  private val aliceId = id("3f2b8c1e-9a4d-4e7f-8b2a-1c5d6e7f8a9b")
  private val bobId   = id("7c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f")

  private def decimal(json: Json): Option[BigDecimal] = json.asNumber.flatMap(_.toBigDecimal)

  test("Money is encoded as a decimal amount, not as cents") {
    assertEquals(decimal(cents(1050).asJson), Some(BigDecimal("10.50")))
    assertEquals(decimal(Money.zero.asJson), Some(BigDecimal(0)))
  }

  test("Money is decoded from a decimal amount, rounding half up to the cent") {
    assertEquals(Json.fromBigDecimal(BigDecimal("10.50")).as[Money].map(_.cents), Right(1050L))
    assertEquals(Json.fromBigDecimal(BigDecimal("0.005")).as[Money].map(_.cents), Right(1L))
  }

  test("Money decoding rejects negative amounts and non-numbers") {
    val negative = Json.fromBigDecimal(BigDecimal("-1")).as[Money]
    assert(negative.left.exists(_.message.contains("Amount cannot be negative")), negative)
    assert(Json.fromString("ten").as[Money].isLeft)
  }

  test("AccountId is encoded as its UUID string and validated on decode") {
    assertEquals(aliceId.asJson, Json.fromString(aliceId.asString))
    assertEquals(Json.fromString(aliceId.asString).as[AccountId], Right(aliceId))
    val invalid = Json.fromString("nope").as[AccountId]
    assert(invalid.left.exists(_.message.contains("Invalid account id: nope")), invalid)
  }

  test("TransactionType uses its case name on the wire") {
    assertEquals(TransactionType.Deposit.asJson, Json.fromString("Deposit"))
    assertEquals(Json.fromString("Transfer").as[TransactionType], Right(TransactionType.Transfer))
    val unknown = Json.fromString("Withdrawal").as[TransactionType]
    assert(unknown.left.exists(_.message.contains("Unknown transaction type: Withdrawal")), unknown)
  }

  test("Account has a stable JSON shape and round-trips") {
    val account = Account(aliceId, "Alice", cents(1050))
    val json    = account.asJson

    assertEquals(json.hcursor.get[String]("id"), Right(aliceId.asString))
    assertEquals(json.hcursor.get[String]("owner"), Right("Alice"))
    assertEquals(json.hcursor.get[BigDecimal]("balance"), Right(BigDecimal("10.50")))
    assertEquals(json.as[Account], Right(account))
  }

  test("Transaction round-trips, with a null counterparty for deposits") {
    val deposit = Transaction(
      UUID.fromString("0b9f5c2a-1d3e-4f5a-8b6c-7d8e9f0a1b2c"),
      aliceId,
      None,
      TransactionType.Deposit,
      cents(1050),
      cents(1050),
      Instant.parse("2026-10-31T12:00:00Z")
    )
    val transfer = deposit.copy(counterpartyId = Some(bobId), txType = TransactionType.Transfer)

    assertEquals(deposit.asJson.hcursor.downField("counterpartyId").focus, Some(Json.Null))
    assertEquals(deposit.asJson.hcursor.get[String]("occurredAt"), Right("2026-10-31T12:00:00Z"))
    assertEquals(deposit.asJson.as[Transaction], Right(deposit))
    assertEquals(transfer.asJson.as[Transaction], Right(transfer))
  }

  test("request bodies decode from the shapes the frontend sends") {
    val transferJson = Json.obj(
      "from"   -> Json.fromString(aliceId.asString),
      "to"     -> Json.fromString(bobId.asString),
      "amount" -> Json.fromBigDecimal(BigDecimal("30.25"))
    )
    assertEquals(
      transferJson.as[JsonCodecs.TransferRequest],
      Right(JsonCodecs.TransferRequest(aliceId.asString, bobId.asString, BigDecimal("30.25")))
    )
    assertEquals(
      Json.obj("owner" -> Json.fromString("Alice")).as[JsonCodecs.CreateAccountRequest],
      Right(JsonCodecs.CreateAccountRequest("Alice"))
    )
    assert(Json.obj("owner" -> Json.fromInt(1)).as[JsonCodecs.CreateAccountRequest].isLeft)
    assert(Json.obj().as[JsonCodecs.AmountRequest].isLeft)
  }
