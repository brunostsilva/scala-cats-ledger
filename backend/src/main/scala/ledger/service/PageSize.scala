package ledger.service

/** How many ledger entries a stream reads from storage at a time, between 1 and [[PageSize.Max]].
  * Being a type, an invalid size can't reach the storage: it's rejected where it's parsed.
  */
opaque type PageSize = Int

object PageSize:

  val Max: Int = 1000

  val Default: PageSize = 500

  def from(n: Int): Either[String, PageSize] =
    if n >= 1 && n <= Max then Right(n) else Left(s"pageSize must be between 1 and $Max, got $n")

  extension (size: PageSize) def value: Int = size
