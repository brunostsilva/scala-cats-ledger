package ledger.domain

import munit.FunSuite

class MoneySpec extends FunSuite:

  private def cents(c: Long): Money = Money.fromCents(c).toOption.get

  test("fromCents accepts zero and positive amounts") {
    assertEquals(Money.fromCents(0L).map(_.cents), Right(0L))
    assertEquals(Money.fromCents(1050L).map(_.cents), Right(1050L))
  }

  test("fromCents rejects negative amounts") {
    assertEquals(Money.fromCents(-1L), Left("Amount cannot be negative: -1"))
  }

  test("fromDecimal converts a decimal amount to cents") {
    assertEquals(Money.fromDecimal(BigDecimal("10.50")).map(_.cents), Right(1050L))
    assertEquals(Money.fromDecimal(BigDecimal("7")).map(_.cents), Right(700L))
  }

  test("fromDecimal rounds to the nearest cent, half up") {
    assertEquals(Money.fromDecimal(BigDecimal("10.005")).map(_.cents), Right(1001L))
    assertEquals(Money.fromDecimal(BigDecimal("10.004")).map(_.cents), Right(1000L))
  }

  test("fromDecimal rejects negative amounts") {
    assert(Money.fromDecimal(BigDecimal("-0.01")).isLeft)
  }

  test("fromDecimal rejects amounts that don't fit in a Long of cents") {
    val huge = BigDecimal("1e30")
    assertEquals(Money.fromDecimal(huge), Left(s"Amount out of range: $huge"))
  }

  test("toBigDecimal exposes the amount with two decimal places") {
    assertEquals(cents(1050).toBigDecimal, BigDecimal("10.50"))
    assertEquals(cents(1050).toBigDecimal.scale, 2)
    assertEquals(Money.zero.toBigDecimal, BigDecimal("0.00"))
  }

  test("addition sums cents and reports overflow instead of wrapping around") {
    assertEquals((cents(150) + cents(50)).map(_.cents), Right(200L))
    assertEquals(cents(Long.MaxValue) + cents(1), Left("Amount overflow"))
  }

  test("subtraction never goes below zero") {
    assertEquals((cents(150) - cents(50)).map(_.cents), Right(100L))
    assertEquals((cents(150) - cents(150)).map(_.cents), Right(0L))
    assertEquals(cents(100) - cents(101), Left("Insufficient funds"))
  }
