package ledger.domain

enum LedgerError:
  case AccountNotFound(id: AccountId)
  case InsufficientFunds(id: AccountId, balance: Money, requested: Money)
  case SameAccountTransfer(id: AccountId)
  case InvalidAmount(message: String)

  /** A stable, machine-friendly code for logs: messages may change, dashboards and alerts shouldn't. */
  def code: String = this match
    case AccountNotFound(_)         => "account_not_found"
    case InsufficientFunds(_, _, _) => "insufficient_funds"
    case SameAccountTransfer(_)     => "same_account"
    case InvalidAmount(_)           => "invalid_amount"
