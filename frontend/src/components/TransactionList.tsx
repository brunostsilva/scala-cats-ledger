import type { Transaction } from '../api'

type Props = {
  transactions: Transaction[]
}

export function TransactionList({ transactions }: Props) {
  if (transactions.length === 0) {
    return <p className="empty">No transactions yet.</p>
  }

  return (
    <table className="transactions">
      <thead>
        <tr>
          <th>Type</th>
          <th>Amount</th>
          <th>Balance after</th>
          <th>When</th>
        </tr>
      </thead>
      <tbody>
        {transactions.map((tx) => (
          <tr key={tx.id}>
            <td>{tx.txType}</td>
            <td>${tx.amount.toFixed(2)}</td>
            <td>${tx.balanceAfter.toFixed(2)}</td>
            <td>{new Date(tx.occurredAt).toLocaleString()}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
