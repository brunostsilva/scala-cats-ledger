package ledger.http

import munit.FunSuite

class TransactionStreamSettingsSpec extends FunSuite:

  test("falls back to the defaults when nothing is set") {
    assertEquals(TransactionStreamSettings.fromEnv(Map.empty), Right(TransactionStreamSettings.Default))
  }

  test("reads the page size and the stream limit from the environment") {
    val settings = TransactionStreamSettings.fromEnv(
      Map("TRANSACTION_STREAM_PAGE_SIZE" -> "200", "TRANSACTION_STREAM_MAX_CONCURRENT" -> "3")
    )
    assertEquals(settings.map(s => (s.defaultPageSize.value, s.maxConcurrentStreams)), Right((200, 3)))
  }

  test("rejects invalid values, naming the variable") {
    val invalid = List(
      "TRANSACTION_STREAM_PAGE_SIZE"      -> "0",
      "TRANSACTION_STREAM_PAGE_SIZE"      -> "1001",
      "TRANSACTION_STREAM_PAGE_SIZE"      -> "lots",
      "TRANSACTION_STREAM_MAX_CONCURRENT" -> "0",
      "TRANSACTION_STREAM_MAX_CONCURRENT" -> "many"
    )
    invalid.foreach { (name, value) =>
      TransactionStreamSettings.fromEnv(Map(name -> value)) match
        case Left(message) => assert(message.contains(name), message)
        case Right(_)      => fail(s"expected $name=$value to be rejected")
    }
  }
