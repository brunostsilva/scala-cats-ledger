package ledger.domain

/** Monetary amount stored internally as integer cents to avoid floating-point rounding errors. */
opaque type Money = Long

object Money:
  val zero: Money = 0L

  def fromCents(cents: Long): Either[String, Money] =
    if cents < 0 then Left(s"Amount cannot be negative: $cents")
    else Right(cents)

  /** Parses a decimal amount (e.g. `10.50`) into cents, rejecting values that don't fit a `Long`. */
  def fromDecimal(amount: BigDecimal): Either[String, Money] =
    val scaledCents = amount.setScale(2, BigDecimal.RoundingMode.HALF_UP).bigDecimal.movePointRight(2)
    try fromCents(scaledCents.longValueExact())
    catch case _: ArithmeticException => Left(s"Amount out of range: $amount")

  extension (m: Money)
    def cents: Long = m
    def toBigDecimal: BigDecimal = BigDecimal(m, 2)
    def +(other: Money): Either[String, Money] =
      try Right(Math.addExact(m, other))
      catch case _: ArithmeticException => Left("Amount overflow")
    def -(other: Money): Either[String, Money] =
      val result = m - other
      if result < 0 then Left("Insufficient funds") else Right(result)
