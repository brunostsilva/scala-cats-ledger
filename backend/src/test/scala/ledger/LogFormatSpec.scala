package ledger

import munit.FunSuite

class LogFormatSpec extends FunSuite:

  test("defaults to text when LOG_FORMAT is not set") {
    assertEquals(LogFormat.validate(None), Right("text"))
  }

  test("accepts text and json") {
    assertEquals(LogFormat.validate(Some("text")), Right("text"))
    assertEquals(LogFormat.validate(Some("json")), Right("json"))
  }

  test("rejects any other value, which would silently disable logging") {
    List("JSON", "plain", "", " json").foreach { value =>
      LogFormat.validate(Some(value)) match
        case Left(error) => assert(error.contains(s"\"$value\""), error)
        case Right(_)    => fail(s"expected \"$value\" to be rejected")
    }
  }
