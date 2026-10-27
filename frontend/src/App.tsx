import { useEffect, useState } from 'react'
import { api, type Account, type Transaction } from './api'
import { CreateAccount } from './components/CreateAccount'
import { AccountCard } from './components/AccountCard'
import { ActionsPanel } from './components/ActionsPanel'
import { TransactionList } from './components/TransactionList'

export default function App() {
  const [accounts, setAccounts] = useState<Account[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [transactions, setTransactions] = useState<Transaction[]>([])
  const [error, setError] = useState<string | null>(null)

  const refreshAccount = async (id: string) => {
    const account = await api.getAccount(id)
    setAccounts((prev) => prev.map((a) => (a.id === id ? account : a)))
  }

  const loadTransactions = async (id: string) => {
    const txs = await api.transactions(id)
    setTransactions(txs)
  }

  useEffect(() => {
    if (selectedId) loadTransactions(selectedId).catch((e) => setError((e as Error).message))
  }, [selectedId])

  const handleCreate = async (owner: string) => {
    try {
      const account = await api.createAccount(owner)
      setAccounts((prev) => [...prev, account])
      setSelectedId(account.id)
      setError(null)
    } catch (e) {
      setError((e as Error).message)
    }
  }

  const handleDeposit = async (id: string, amount: number) => {
    try {
      await api.deposit(id, amount)
      await refreshAccount(id)
      if (id === selectedId) await loadTransactions(id)
      setError(null)
    } catch (e) {
      setError((e as Error).message)
    }
  }

  const handleTransfer = async (from: string, to: string, amount: number) => {
    try {
      await api.transfer(from, to, amount)
      await refreshAccount(from)
      await refreshAccount(to)
      if (selectedId === from || selectedId === to) await loadTransactions(selectedId)
      setError(null)
    } catch (e) {
      setError((e as Error).message)
    }
  }

  return (
    <div className="app">
      <header>
        <h1>Mini Ledger</h1>
        <p>A tiny ledger built with Scala, Cats Effect &amp; React.</p>
      </header>

      {error && <div className="error">{error}</div>}

      <div className="layout">
        <aside>
          <CreateAccount onCreate={handleCreate} />
          <div className="accounts">
            {accounts.map((a) => (
              <AccountCard key={a.id} account={a} selected={a.id === selectedId} onSelect={setSelectedId} />
            ))}
          </div>
        </aside>

        <main>
          <ActionsPanel accounts={accounts} onDeposit={handleDeposit} onTransfer={handleTransfer} />
          <h2>Transaction history</h2>
          <TransactionList transactions={transactions} />
        </main>
      </div>
    </div>
  )
}
