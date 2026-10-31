import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { CreateAccount } from './CreateAccount'

describe('CreateAccount', () => {
  it('submits the trimmed owner name and clears the field', async () => {
    const onCreate = vi.fn()
    render(<CreateAccount onCreate={onCreate} />)
    const input = screen.getByPlaceholderText('Owner name')

    await userEvent.type(input, '  Alice  ')
    await userEvent.click(screen.getByRole('button', { name: 'Create' }))

    expect(onCreate).toHaveBeenCalledExactlyOnceWith('Alice')
    expect(input).toHaveValue('')
  })

  it('submits with the Enter key', async () => {
    const onCreate = vi.fn()
    render(<CreateAccount onCreate={onCreate} />)

    await userEvent.type(screen.getByPlaceholderText('Owner name'), 'Bob{Enter}')

    expect(onCreate).toHaveBeenCalledExactlyOnceWith('Bob')
  })

  it('ignores blank names', async () => {
    const onCreate = vi.fn()
    render(<CreateAccount onCreate={onCreate} />)

    await userEvent.type(screen.getByPlaceholderText('Owner name'), '   ')
    await userEvent.click(screen.getByRole('button', { name: 'Create' }))

    expect(onCreate).not.toHaveBeenCalled()
  })
})
