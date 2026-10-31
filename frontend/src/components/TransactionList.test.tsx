import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { bob, transaction } from '../test/fixtures'
import { TransactionList } from './TransactionList'

describe('TransactionList', () => {
  it('shows an empty state when there are no transactions', () => {
    render(<TransactionList transactions={[]} />)

    expect(screen.getByText('No transactions yet.')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('renders one row per transaction with formatted amounts', () => {
    render(
      <TransactionList
        transactions={[
          transaction({ id: 'tx-1', txType: 'Deposit', amount: 100, balanceAfter: 100 }),
          transaction({ id: 'tx-2', txType: 'Transfer', amount: 30.5, balanceAfter: 69.5, counterpartyId: bob.id })
        ]}
      />
    )

    const rows = screen.getAllByRole('row')
    expect(rows).toHaveLength(3) // header + 2 transactions
    expect(within(rows[0]).getAllByRole('columnheader').map((th) => th.textContent)).toEqual([
      'Type',
      'Amount',
      'Balance after',
      'When'
    ])
    expect(within(rows[1]).getAllByRole('cell').map((td) => td.textContent).slice(0, 3)).toEqual([
      'Deposit',
      '$100.00',
      '$100.00'
    ])
    expect(within(rows[2]).getAllByRole('cell').map((td) => td.textContent).slice(0, 3)).toEqual([
      'Transfer',
      '$30.50',
      '$69.50'
    ])
  })

  it('formats the timestamp for the local user', () => {
    const occurredAt = '2026-10-31T12:00:00Z'
    render(<TransactionList transactions={[transaction({ occurredAt })]} />)

    expect(screen.getByText(new Date(occurredAt).toLocaleString())).toBeInTheDocument()
  })
})
