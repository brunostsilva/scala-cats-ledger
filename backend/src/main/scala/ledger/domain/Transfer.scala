package ledger.domain

import java.time.Instant
import java.util.UUID

/** A completed transfer, recorded once. Each account's history also keeps its own leg of it
  * (a [[Transaction]] with the same id), with the balance that account was left with.
  */
final case class Transfer(id: UUID, from: AccountId, to: AccountId, amount: Money, occurredAt: Instant)
