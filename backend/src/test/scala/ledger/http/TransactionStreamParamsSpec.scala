package ledger.http

import munit.FunSuite
import ledger.domain.AccountId
import ledger.service.{PageSize, TransactionQuery}

/** Parsing the query parameters of `GET /transactions/stream`, without HTTP. */
class TransactionStreamParamsSpec extends FunSuite:

  private val defaultPageSize = PageSize.from(250).toOption.get
  private val accountId       = "00000000-0000-4000-8000-000000000001"

  private def parse(account: Option[String] = None, after: Option[String] = None, pageSize: Option[String] = None) =
    TransactionStreamParams.parse(account, after, pageSize, defaultPageSize)

  test("without parameters, streams the whole ledger from the start with the default page size") {
    assertEquals(parse(), Right(TransactionQuery(None, after = 0L, pageSize = defaultPageSize)))
  }

  test("parses every parameter") {
    val query = parse(Some(accountId), Some("42"), Some("1000"))
    assertEquals(query.map(_.account), Right(AccountId.fromString(accountId).toOption))
    assertEquals(query.map(_.after), Right(42L))
    assertEquals(query.map(_.pageSize.value), Right(1000))
  }

  test("rejects each invalid value with a message naming what is wrong") {
    val invalid = List(
      parse(account = Some("not-a-uuid"))   -> "Invalid account id",
      parse(after = Some("-1"))             -> "after must be a non-negative sequence number",
      parse(after = Some("soon"))           -> "after must be a non-negative sequence number",
      parse(pageSize = Some("0"))           -> "pageSize must be between 1 and 1000",
      parse(pageSize = Some("1001"))        -> "pageSize must be between 1 and 1000",
      parse(pageSize = Some("lots"))        -> "pageSize must be a number"
    )
    invalid.foreach { (result, expected) =>
      result match
        case Left(message) => assert(message.startsWith(expected), message)
        case Right(query)  => fail(s"expected a rejection containing \"$expected\", got $query")
    }
  }
