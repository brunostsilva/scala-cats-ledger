import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { alice, bob } from '../test/fixtures'
import { ActionsPanel } from './ActionsPanel'

function renderPanel() {
  const onDeposit = vi.fn()
  const onTransfer = vi.fn()
  render(<ActionsPanel accounts={[alice, bob]} onDeposit={onDeposit} onTransfer={onTransfer} />)
  const [depositAmount, transferAmount] = screen.getAllByPlaceholderText('Amount')
  return { onDeposit, onTransfer, depositAmount, transferAmount }
}

describe('ActionsPanel', () => {
  it('lists every account as an option in the three selects', () => {
    renderPanel()

    for (const label of ['Account to deposit into', 'Transfer from account', 'Transfer to account']) {
      const options = screen.getByRole('combobox', { name: label }).querySelectorAll('option')
      expect([...options].map((o) => o.textContent)).toEqual([expect.any(String), 'Alice', 'Bob'])
    }
  })

  describe('deposit', () => {
    it('deposits the amount into the selected account and clears the amount', async () => {
      const { onDeposit, depositAmount } = renderPanel()

      await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Account to deposit into' }), bob.id)
      await userEvent.type(depositAmount, '25.50')
      await userEvent.click(screen.getByRole('button', { name: 'Deposit' }))

      expect(onDeposit).toHaveBeenCalledExactlyOnceWith(bob.id, 25.5)
      expect(depositAmount).toHaveValue(null)
    })

    it('does nothing without an account', async () => {
      const { onDeposit, depositAmount } = renderPanel()

      await userEvent.type(depositAmount, '10')
      await userEvent.click(screen.getByRole('button', { name: 'Deposit' }))

      expect(onDeposit).not.toHaveBeenCalled()
    })

    it.each(['0', '-5'])('does nothing for a non-positive amount (%s)', async (amount) => {
      const { onDeposit, depositAmount } = renderPanel()

      await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Account to deposit into' }), alice.id)
      await userEvent.type(depositAmount, amount)
      await userEvent.click(screen.getByRole('button', { name: 'Deposit' }))

      expect(onDeposit).not.toHaveBeenCalled()
    })
  })

  describe('transfer', () => {
    it('transfers between two different accounts and clears the amount', async () => {
      const { onTransfer, transferAmount } = renderPanel()

      await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Transfer from account' }), alice.id)
      await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Transfer to account' }), bob.id)
      await userEvent.type(transferAmount, '30')
      await userEvent.click(screen.getByRole('button', { name: 'Transfer' }))

      expect(onTransfer).toHaveBeenCalledExactlyOnceWith(alice.id, bob.id, 30)
      expect(transferAmount).toHaveValue(null)
    })

    it('refuses to transfer from an account to itself', async () => {
      const { onTransfer, transferAmount } = renderPanel()

      await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Transfer from account' }), alice.id)
      await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Transfer to account' }), alice.id)
      await userEvent.type(transferAmount, '30')
      await userEvent.click(screen.getByRole('button', { name: 'Transfer' }))

      expect(onTransfer).not.toHaveBeenCalled()
    })

    it('needs both accounts and a positive amount', async () => {
      const { onTransfer, transferAmount } = renderPanel()

      await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Transfer from account' }), alice.id)
      await userEvent.type(transferAmount, '30')
      await userEvent.click(screen.getByRole('button', { name: 'Transfer' }))
      expect(onTransfer).not.toHaveBeenCalled()

      await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Transfer to account' }), bob.id)
      await userEvent.clear(transferAmount)
      await userEvent.type(transferAmount, '0')
      await userEvent.click(screen.getByRole('button', { name: 'Transfer' }))
      expect(onTransfer).not.toHaveBeenCalled()
    })
  })
})
