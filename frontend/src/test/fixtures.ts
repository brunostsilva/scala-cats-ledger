import type { Account, Transaction } from '../api'
import type { LedgerEntry } from '../ledgerStream'

export const alice: Account = { id: 'acc-alice', owner: 'Alice', balance: 0 }
export const bob: Account = { id: 'acc-bob', owner: 'Bob', balance: 50 }

export function transaction(overrides: Partial<Transaction> = {}): Transaction {
  return {
    id: 'tx-1',
    accountId: alice.id,
    counterpartyId: null,
    txType: 'Deposit',
    amount: 100,
    balanceAfter: 100,
    occurredAt: '2026-10-31T12:00:00Z',
    ...overrides
  }
}

/** The ledger entry at `seq`: a 10.00 deposit, into Alice's account unless overridden. */
export function ledgerEntry(seq: number, overrides: Partial<LedgerEntry> = {}): LedgerEntry {
  return {
    ...transaction({ id: `tx-${seq}`, amount: 10, balanceAfter: 10 * seq }),
    seq,
    ...overrides
  }
}
