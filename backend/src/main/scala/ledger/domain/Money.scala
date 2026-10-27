package ledger.domain

/** Monetary amount stored internally as integer cents to avoid floating-point rounding errors. */
opaque type Money = Long

object Money:
  val zero: Money = 0L

  def fromCents(cents: Long): Either[String, Money] =
    if cents < 0 then Left(s"Amount cannot be negative: $cents")
    else Right(cents)

  extension (m: Money)
    def cents: Long = m
    def toDouble: Double = m / 100.0
    def +(other: Money): Money = m + other
    def -(other: Money): Either[String, Money] =
      val result = m - other
      if result < 0 then Left("Insufficient funds") else Right(result)
