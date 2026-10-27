package ledger.domain

final case class Account(id: AccountId, owner: String, balance: Money)
