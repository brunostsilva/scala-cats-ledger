import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { API_URL } from '../config'
import { FakeLedgerServer } from '../test/fakeLedgerServer'
import { alice, bob, ledgerEntry } from '../test/fixtures'
import type { LedgerStreamOptions } from '../useLedgerStream'
import { LedgerStream } from './LedgerStream'

/**
 * The component, its hook and the streaming client together, over real HTTP against a fake backend
 * that streams NDJSON in chunks: nothing in the app is mocked, only the server is a stand-in.
 */
const server = new FakeLedgerServer()
let serverUrl = ''
const realFetch = globalThis.fetch

beforeAll(async () => {
  serverUrl = await server.start()
})

afterAll(() => server.stop())

beforeEach(() => {
  server.reset()
  // The app calls the configured API URL: send it to the fake backend instead. jsdom's AbortSignal
  // isn't accepted by Node's fetch, so it's left out here; the client also cancels the body itself
  // when aborted, which is what these tests exercise.
  vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) =>
    realFetch(String(input).replace(API_URL, serverUrl), { ...init, signal: undefined })
  )
})

/** Entries 1..n, alternating between Alice and Bob. */
function ledger(n: number) {
  return Array.from({ length: n }, (_, i) => ledgerEntry(i + 1, { accountId: i % 2 === 0 ? alice.id : bob.id }))
}

const fast: LedgerStreamOptions = { followIntervalMs: 30, resumeDelayMs: 10 }

function renderStream(options: LedgerStreamOptions = fast) {
  render(<LedgerStream accounts={[alice, bob]} options={options} />)
}

const startButton = () => screen.getByRole('button', { name: 'Stream ledger' })
const status = () => screen.getByRole('status')

/** The `#` column of the streamed table. */
function shownSeqs(): number[] {
  const table = screen.getByRole('table', { name: 'Streamed transactions' })
  return within(table)
    .getAllByRole('row')
    .slice(1)
    .map((row) => Number(within(row).getAllByRole('cell')[0].textContent))
}

describe('LedgerStream against a streaming backend', () => {
  it('streams the whole ledger into the table, with owners, then says it is complete', async () => {
    server.entries = ledger(12)
    renderStream()

    await userEvent.click(startButton())

    expect(await screen.findByText('Complete: 12 transactions, up to #12')).toBeInTheDocument()
    expect(shownSeqs()).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12])
    const firstRow = screen.getAllByRole('row')[1]
    expect(within(firstRow).getAllByRole('cell').map((cell) => cell.textContent).slice(0, 4)).toEqual([
      '1',
      'Alice',
      'Deposit',
      '$10.00'
    ])
    expect(server.requests).toEqual([{ account: null, after: 0 }])
  })

  it("streams one account's history when it's picked in the filter", async () => {
    server.entries = ledger(10)
    renderStream()

    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Stream account' }), bob.id)
    await userEvent.click(startButton())

    expect(await screen.findByText('Complete: 5 transactions, up to #10')).toBeInTheDocument()
    expect(shownSeqs()).toEqual([2, 4, 6, 8, 10])
    expect(server.requests).toEqual([{ account: bob.id, after: 0 }])
  })

  it('resumes from the last transaction received when the connection drops mid-stream', async () => {
    server.entries = ledger(12)
    server.faults = [{ kind: 'cut', afterEntries: 5 }]
    renderStream()

    await userEvent.click(startButton())

    expect(await screen.findByText('Complete: 12 transactions, up to #12')).toBeInTheDocument()
    // Nothing lost, nothing twice.
    expect(shownSeqs()).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12])
    expect(server.requests.map((r) => r.after)).toEqual([0, 5])
  })

  it('waits for Retry-After when the server is at its limit of streams, then streams', async () => {
    server.entries = ledger(3)
    server.faults = [{ kind: 'busy', retryAfterSeconds: 1 }]
    renderStream()

    await userEvent.click(startButton())

    expect(await screen.findByText('The server is busy, retrying in 1s…')).toBeInTheDocument()
    expect(await screen.findByText('Complete: 3 transactions, up to #3', {}, { timeout: 3000 })).toBeInTheDocument()
    expect(server.requests).toHaveLength(2)
  })

  it('follows new transactions once the ledger has been streamed', async () => {
    server.entries = ledger(4)
    renderStream()

    await userEvent.click(screen.getByRole('checkbox', { name: 'Follow new transactions' }))
    await userEvent.click(startButton())
    expect(await screen.findByText('Following new transactions: 4 transactions, up to #4')).toBeInTheDocument()

    server.entries.push(ledgerEntry(5, { accountId: bob.id }))

    expect(await screen.findByText('Following new transactions: 5 transactions, up to #5')).toBeInTheDocument()
    expect(shownSeqs()).toEqual([1, 2, 3, 4, 5])
    // Polls ask only for what's new.
    expect(server.requests.map((r) => r.after)).toContain(4)
    await userEvent.click(screen.getByRole('button', { name: 'Stop' }))
  })

  it('streams the ledger again from the start when the server says it was reset', async () => {
    server.entries = ledger(6)
    renderStream()

    await userEvent.click(screen.getByRole('checkbox', { name: 'Follow new transactions' }))
    await userEvent.click(startButton())
    expect(await screen.findByText('Following new transactions: 6 transactions, up to #6')).toBeInTheDocument()

    // The in-memory backend restarted: seq starts again at 1, so after=6 is beyond its end (409).
    server.entries = [ledgerEntry(1, { accountId: bob.id, amount: 42 }), ledgerEntry(2, { accountId: bob.id })]

    expect(await screen.findByText('Following new transactions: 2 transactions, up to #2')).toBeInTheDocument()
    expect(shownSeqs()).toEqual([1, 2])
    expect(server.requests.map((r) => r.after)).toEqual(expect.arrayContaining([6, 0]))
    await userEvent.click(screen.getByRole('button', { name: 'Stop' }))
  })

  it('stops reading and closes the connection when stopped', async () => {
    server.entries = ledger(300)
    server.linesPerChunk = 1
    server.chunkDelayMs = 20
    renderStream()

    await userEvent.click(startButton())
    await screen.findByRole('table', { name: 'Streamed transactions' })
    await userEvent.click(screen.getByRole('button', { name: 'Stop' }))

    expect(status()).toHaveTextContent(/^Stopped after \d+ transactions?$/)
    await vi.waitFor(() => expect(server.abandoned).toBe(1))
    const shown = shownSeqs().length
    await new Promise((resolve) => setTimeout(resolve, 100))
    expect(shownSeqs()).toHaveLength(shown) // nothing more arrives
    expect(shown).toBeLessThan(300)
    expect(startButton()).toBeEnabled()
  })

  it("shows the server's message when the stream is refused", async () => {
    server.entries = ledger(2)
    render(<LedgerStream accounts={[alice, bob, { id: 'acc-gone', owner: 'Gone', balance: 0 }]} options={fast} />)

    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Stream account' }), 'acc-gone')
    await userEvent.click(startButton())

    expect(await screen.findByRole('alert')).toHaveTextContent('Account acc-gone not found')
    expect(status()).toHaveTextContent('Failed after 0 transactions')
  })

  it('keeps only the latest rows of a long ledger, while counting all of it', async () => {
    server.entries = ledger(1200)
    server.linesPerChunk = 100
    renderStream({ ...fast, maxEntries: 50 })

    await userEvent.click(startButton())

    expect(await screen.findByText('Complete: 1,200 transactions, up to #1200', {}, { timeout: 4000 })).toBeInTheDocument()
    expect(shownSeqs()).toHaveLength(50)
    expect(shownSeqs()[49]).toBe(1200)
    expect(screen.getByText('Showing the latest 50 of 1,200 transactions.')).toBeInTheDocument()
  })
})
