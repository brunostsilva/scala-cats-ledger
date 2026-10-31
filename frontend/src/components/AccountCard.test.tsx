import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { bob } from '../test/fixtures'
import { AccountCard } from './AccountCard'

describe('AccountCard', () => {
  it('shows the owner, the balance with two decimals and the account id', () => {
    render(<AccountCard account={{ ...bob, balance: 12.5 }} selected={false} onSelect={() => {}} />)

    const card = screen.getByRole('button', { name: /Bob/ })
    expect(card).toHaveTextContent('Bob')
    expect(card).toHaveTextContent('$12.50')
    expect(card).toHaveTextContent(bob.id)
  })

  it('reflects the selection state for assistive technologies and styling', () => {
    const { rerender } = render(<AccountCard account={bob} selected={false} onSelect={() => {}} />)
    const card = screen.getByRole('button', { name: /Bob/ })
    expect(card).toHaveAttribute('aria-pressed', 'false')
    expect(card).not.toHaveClass('selected')

    rerender(<AccountCard account={bob} selected onSelect={() => {}} />)

    expect(card).toHaveAttribute('aria-pressed', 'true')
    expect(card).toHaveClass('account-card', 'selected')
  })

  it('selects the account when clicked', async () => {
    const onSelect = vi.fn()
    render(<AccountCard account={bob} selected={false} onSelect={onSelect} />)

    await userEvent.click(screen.getByRole('button', { name: /Bob/ }))

    expect(onSelect).toHaveBeenCalledExactlyOnceWith(bob.id)
  })
})
