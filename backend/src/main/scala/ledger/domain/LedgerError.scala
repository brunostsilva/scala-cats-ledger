package ledger.domain

enum LedgerError:
  case AccountNotFound(id: AccountId)
  case InsufficientFunds(id: AccountId, balance: Money, requested: Money)
  case SameAccountTransfer(id: AccountId)
  case InvalidAmount(message: String)
