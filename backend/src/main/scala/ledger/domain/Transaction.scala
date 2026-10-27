package ledger.domain

import java.time.Instant
import java.util.UUID

enum TransactionType:
  case Deposit, Transfer

final case class Transaction(
    id: UUID,
    accountId: AccountId,
    counterpartyId: Option[AccountId],
    txType: TransactionType,
    amount: Money,
    balanceAfter: Money,
    occurredAt: Instant
)
