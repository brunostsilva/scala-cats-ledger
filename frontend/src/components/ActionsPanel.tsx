import { useState } from 'react'
import type { Account } from '../api'

type Props = {
  accounts: Account[]
  onDeposit: (id: string, amount: number) => void
  onTransfer: (from: string, to: string, amount: number) => void
}

export function ActionsPanel({ accounts, onDeposit, onTransfer }: Props) {
  const [depositTarget, setDepositTarget] = useState('')
  const [depositAmount, setDepositAmount] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [transferAmount, setTransferAmount] = useState('')

  return (
    <div className="card">
      <h2>Deposit</h2>
      <form
        onSubmit={(e) => {
          e.preventDefault()
          const amount = parseFloat(depositAmount)
          if (depositTarget && amount > 0) {
            onDeposit(depositTarget, amount)
            setDepositAmount('')
          }
        }}
      >
        <select value={depositTarget} onChange={(e) => setDepositTarget(e.target.value)}>
          <option value="">Select account</option>
          {accounts.map((a) => (
            <option key={a.id} value={a.id}>
              {a.owner}
            </option>
          ))}
        </select>
        <input
          type="number"
          step="0.01"
          placeholder="Amount"
          value={depositAmount}
          onChange={(e) => setDepositAmount(e.target.value)}
        />
        <button type="submit">Deposit</button>
      </form>

      <h2>Transfer</h2>
      <form
        onSubmit={(e) => {
          e.preventDefault()
          const amount = parseFloat(transferAmount)
          if (from && to && from !== to && amount > 0) {
            onTransfer(from, to, amount)
            setTransferAmount('')
          }
        }}
      >
        <select value={from} onChange={(e) => setFrom(e.target.value)}>
          <option value="">From</option>
          {accounts.map((a) => (
            <option key={a.id} value={a.id}>
              {a.owner}
            </option>
          ))}
        </select>
        <select value={to} onChange={(e) => setTo(e.target.value)}>
          <option value="">To</option>
          {accounts.map((a) => (
            <option key={a.id} value={a.id}>
              {a.owner}
            </option>
          ))}
        </select>
        <input
          type="number"
          step="0.01"
          placeholder="Amount"
          value={transferAmount}
          onChange={(e) => setTransferAmount(e.target.value)}
        />
        <button type="submit">Transfer</button>
      </form>
    </div>
  )
}
