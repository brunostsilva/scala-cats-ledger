import { useMemo, useState } from 'react'
import type { Account } from '../api'
import { useLedgerStream, type LedgerStreamOptions, type LedgerStreamState } from '../useLedgerStream'

type Props = {
  /** Known accounts, for the filter and to show owners instead of ids. */
  accounts: Account[]
  options?: LedgerStreamOptions
}

const count = (n: number) => `${n.toLocaleString('en-US')} transaction${n === 1 ? '' : 's'}`

function describe({ status, received, lastSeq, message }: LedgerStreamState): string {
  switch (status) {
    case 'idle':
      return 'Streams the whole ledger, or one account, as the server reads it.'
    case 'streaming':
      return message ?? `Streaming… ${count(received)} received`
    case 'complete':
      return `Complete: ${count(received)}, up to #${lastSeq}`
    case 'following':
      return `Following new transactions: ${count(received)}, up to #${lastSeq}`
    case 'resuming':
    case 'waiting':
      return message ?? 'Reconnecting…'
    case 'stopped':
      return `Stopped after ${count(received)}`
    case 'failed':
      return `Failed after ${count(received)}`
  }
}

/** The ledger streamed live from `GET /transactions/stream`, newest rows at the bottom. */
export function LedgerStream({ accounts, options }: Props) {
  const stream = useLedgerStream(options)
  const [account, setAccount] = useState('')
  const [follow, setFollow] = useState(false)
  const owners = useMemo(() => new Map(accounts.map((a) => [a.id, a.owner])), [accounts])
  const ownerOf = (id: string) => owners.get(id) ?? id.slice(0, 8)

  return (
    <section className="card ledger-stream" aria-labelledby="ledger-stream-title">
      <h2 id="ledger-stream-title">Ledger stream</h2>

      <div className="stream-controls">
        <label>
          Account
          <select
            aria-label="Stream account"
            value={account}
            disabled={stream.running}
            onChange={(e) => setAccount(e.target.value)}
          >
            <option value="">All accounts</option>
            {accounts.map((a) => (
              <option key={a.id} value={a.id}>
                {a.owner}
              </option>
            ))}
          </select>
        </label>
        <label className="checkbox">
          <input type="checkbox" checked={follow} disabled={stream.running} onChange={(e) => setFollow(e.target.checked)} />
          Follow new transactions
        </label>
        {stream.running ? (
          <button type="button" onClick={stream.stop}>
            Stop
          </button>
        ) : (
          <button type="button" onClick={() => stream.start(account || null, follow)}>
            Stream ledger
          </button>
        )}
      </div>

      <p className="stream-status" role="status">
        {describe(stream)}
      </p>
      {stream.status === 'failed' && stream.message && (
        <p className="error" role="alert">
          {stream.message}
        </p>
      )}

      {stream.entries.length > 0 && (
        <>
          <table className="transactions" aria-label="Streamed transactions">
            <thead>
              <tr>
                <th>#</th>
                <th>Account</th>
                <th>Type</th>
                <th>Amount</th>
                <th>Balance after</th>
                <th>When</th>
              </tr>
            </thead>
            <tbody>
              {stream.entries.map((entry) => (
                <tr key={entry.seq}>
                  <td>{entry.seq}</td>
                  <td>{ownerOf(entry.accountId)}</td>
                  <td>{entry.txType}</td>
                  <td>${entry.amount.toFixed(2)}</td>
                  <td>${entry.balanceAfter.toFixed(2)}</td>
                  <td>{new Date(entry.occurredAt).toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {stream.received > stream.entries.length && (
            <p className="empty">
              Showing the latest {stream.entries.length.toLocaleString('en-US')} of {count(stream.received)}.
            </p>
          )}
        </>
      )}
    </section>
  )
}
