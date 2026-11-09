package ledger.service

import ledger.domain.AccountId

/** Which ledger entries to stream.
  *
  * @param account only the entries of this account (its own history), or the whole ledger
  * @param after   only entries with a greater `seq`: 0 for all, or the last `seq` a reader saw to resume
  * @param pageSize how many entries are read from storage at a time
  */
final case class TransactionQuery(
    account: Option[AccountId] = None,
    after: Long = 0L,
    pageSize: PageSize = PageSize.Default
)
