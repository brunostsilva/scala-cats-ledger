package ledger.domain

/** A transaction as recorded in the ledger, with its position in it. `seq` starts at 1 and grows by
  * one per entry across all accounts, so it orders the whole ledger and lets a reader resume after
  * the last entry it saw.
  */
final case class LedgerEntry(seq: Long, transaction: Transaction)
