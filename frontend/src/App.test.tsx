import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { api, type Account } from './api'
import { alice, bob, transaction } from './test/fixtures'

vi.mock('./api', () => ({
  api: {
    createAccount: vi.fn(),
    getAccount: vi.fn(),
    deposit: vi.fn(),
    transfer: vi.fn(),
    transactions: vi.fn()
  }
}))

const mockedApi = vi.mocked(api)

async function createAccount(account: Account) {
  mockedApi.createAccount.mockResolvedValueOnce(account)
  await userEvent.type(screen.getByPlaceholderText('Owner name'), account.owner)
  await userEvent.click(screen.getByRole('button', { name: 'Create' }))
  return screen.findByRole('button', { name: new RegExp(account.owner) })
}

describe('App', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    mockedApi.transactions.mockResolvedValue([])
  })

  it('creates an account, selects it and loads its (empty) history', async () => {
    render(<App />)

    const card = await createAccount(alice)

    expect(mockedApi.createAccount).toHaveBeenCalledExactlyOnceWith('Alice')
    expect(card).toHaveAttribute('aria-pressed', 'true')
    expect(mockedApi.transactions).toHaveBeenCalledWith(alice.id)
    expect(await screen.findByText('No transactions yet.')).toBeInTheDocument()
  })

  it('deposits, then refreshes the balance and the transaction history', async () => {
    render(<App />)
    const card = await createAccount(alice)
    const afterDeposit = { ...alice, balance: 100 }
    mockedApi.deposit.mockResolvedValue(afterDeposit)
    mockedApi.getAccount.mockResolvedValue(afterDeposit)
    mockedApi.transactions.mockResolvedValue([transaction({ amount: 100, balanceAfter: 100 })])

    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Account to deposit into' }), alice.id)
    await userEvent.type(screen.getAllByPlaceholderText('Amount')[0], '100')
    await userEvent.click(screen.getByRole('button', { name: 'Deposit' }))

    expect(mockedApi.deposit).toHaveBeenCalledExactlyOnceWith(alice.id, 100)
    expect(await within(card).findByText('$100.00')).toBeInTheDocument()
    const historyRow = await screen.findByRole('row', { name: /Deposit/ })
    expect(historyRow).toHaveTextContent('$100.00')
  })

  it('transfers, then refreshes both accounts', async () => {
    render(<App />)
    const aliceCard = await createAccount({ ...alice, balance: 100 })
    const bobCard = await createAccount(bob)
    const balances: Record<string, number> = { [alice.id]: 70, [bob.id]: 80 }
    mockedApi.transfer.mockResolvedValue({
      from: { ...alice, balance: 70 },
      to: { ...bob, balance: 80 }
    })
    mockedApi.getAccount.mockImplementation(async (id) => ({ ...(id === alice.id ? alice : bob), balance: balances[id] }))

    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Transfer from account' }), alice.id)
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Transfer to account' }), bob.id)
    await userEvent.type(screen.getAllByPlaceholderText('Amount')[1], '30')
    await userEvent.click(screen.getByRole('button', { name: 'Transfer' }))

    expect(mockedApi.transfer).toHaveBeenCalledExactlyOnceWith(alice.id, bob.id, 30)
    expect(await within(aliceCard).findByText('$70.00')).toBeInTheDocument()
    expect(await within(bobCard).findByText('$80.00')).toBeInTheDocument()
    expect(mockedApi.getAccount).toHaveBeenCalledWith(alice.id)
    expect(mockedApi.getAccount).toHaveBeenCalledWith(bob.id)
  })

  it('selecting another account loads its history', async () => {
    render(<App />)
    const aliceCard = await createAccount(alice)
    await createAccount(bob)
    mockedApi.transactions.mockClear()

    await userEvent.click(aliceCard)

    expect(aliceCard).toHaveAttribute('aria-pressed', 'true')
    expect(mockedApi.transactions).toHaveBeenCalledWith(alice.id)
  })

  it('shows the API error message and clears it after the next success', async () => {
    render(<App />)
    mockedApi.createAccount.mockRejectedValueOnce(new Error('Owner already exists'))

    await userEvent.type(screen.getByPlaceholderText('Owner name'), 'Alice')
    await userEvent.click(screen.getByRole('button', { name: 'Create' }))

    expect(await screen.findByText('Owner already exists')).toBeInTheDocument()

    await createAccount(bob)

    expect(screen.queryByText('Owner already exists')).not.toBeInTheDocument()
  })
})
