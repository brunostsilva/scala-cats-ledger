const API_URL = import.meta.env.VITE_API_URL ?? 'http://localhost:8080'

export type Account = {
  id: string
  owner: string
  balance: number
}

export type TransactionType = 'Deposit' | 'Transfer'

export type Transaction = {
  id: string
  accountId: string
  counterpartyId: string | null
  txType: TransactionType
  amount: number
  balanceAfter: number
  occurredAt: string
}

export type TransferResult = {
  from: Account
  to: Account
}

async function handle<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const body = await res.json().catch(() => ({ message: res.statusText }))
    throw new Error(body.message ?? 'Request failed')
  }
  return res.json() as Promise<T>
}

export const api = {
  createAccount: (owner: string): Promise<Account> =>
    fetch(`${API_URL}/accounts`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ owner })
    }).then(handle<Account>),

  getAccount: (id: string): Promise<Account> => fetch(`${API_URL}/accounts/${id}`).then(handle<Account>),

  deposit: (id: string, amount: number): Promise<Account> =>
    fetch(`${API_URL}/accounts/${id}/deposit`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ amount })
    }).then(handle<Account>),

  transfer: (from: string, to: string, amount: number): Promise<TransferResult> =>
    fetch(`${API_URL}/transfers`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ from, to, amount })
    }).then(handle<TransferResult>),

  transactions: (id: string): Promise<Transaction[]> =>
    fetch(`${API_URL}/accounts/${id}/transactions`).then(handle<Transaction[]>)
}
