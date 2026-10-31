import type { Account, Transaction } from '../api'

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
