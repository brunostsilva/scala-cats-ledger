package ledger.domain

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite

class AccountIdSpec extends CatsEffectSuite:

  test("fromString parses a UUID and asString renders it back") {
    val raw = "3f2b8c1e-9a4d-4e7f-8b2a-1c5d6e7f8a9b"
    assertEquals(AccountId.fromString(raw).map(_.asString), Right(raw))
  }

  test("fromString rejects anything that is not a UUID") {
    assertEquals(AccountId.fromString("not-an-id").map(_.asString), Left("Invalid account id: not-an-id"))
    assert(AccountId.fromString("").isLeft)
  }

  test("random ids round-trip through their string form") {
    AccountId.random[IO].map { id =>
      assertEquals(AccountId.fromString(id.asString), Right(id))
    }
  }

  test("random generates distinct ids") {
    List.fill(100)(AccountId.random[IO]).sequence.map { ids =>
      assertEquals(ids.distinct.size, 100)
    }
  }
