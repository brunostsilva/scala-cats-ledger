package ledger.service

import munit.FunSuite

class PageSizeSpec extends FunSuite:

  test("accepts sizes from 1 to the maximum") {
    assertEquals(PageSize.from(1).map(_.value), Right(1))
    assertEquals(PageSize.from(PageSize.Max).map(_.value), Right(PageSize.Max))
    assertEquals(PageSize.Default.value, 500)
  }

  test("rejects zero, negative and oversized pages with a message") {
    List(0, -5, PageSize.Max + 1).foreach { n =>
      PageSize.from(n) match
        case Left(message) => assert(message.contains(n.toString), message)
        case Right(_)      => fail(s"expected $n to be rejected")
    }
  }
