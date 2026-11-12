import { createServer, type IncomingMessage, type ServerResponse } from 'node:http'
import type { AddressInfo } from 'node:net'
import type { LedgerEntry } from '../ledgerStream'

/** A request the fake backend received. */
export type ReceivedRequest = { account: string | null; after: number }

/** What the next stream request gets instead of a normal response. */
export type Fault =
  /** Sends this many entries, then drops the connection, like a server dying mid-stream. */
  | { kind: 'cut'; afterEntries: number }
  /** A 503 with `Retry-After`, like the backend at its limit of concurrent streams. */
  | { kind: 'busy'; retryAfterSeconds: number }

const delay = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

/**
 * A real HTTP server playing the backend's `GET /transactions/stream`, as documented in the README:
 * NDJSON, oldest first, `account` and `after` filters, an end line only when the stream completes,
 * 404 for an unknown account, 409 for an `after` beyond the end, and 503 with `Retry-After`. The body
 * is written in small chunks with pauses, so the client really reads it as a stream.
 */
export class FakeLedgerServer {
  entries: LedgerEntry[] = []
  /** Accounts that exist, besides those with entries. */
  accounts = new Set<string>()
  faults: Fault[] = []
  linesPerChunk = 3
  chunkDelayMs = 2
  readonly requests: ReceivedRequest[] = []
  /** Responses the client closed before their end. */
  abandoned = 0

  private readonly server = createServer((req, res) => void this.handle(req, res))

  async start(): Promise<string> {
    await new Promise<void>((resolve) => this.server.listen(0, '127.0.0.1', resolve))
    const { port } = this.server.address() as AddressInfo
    return `http://127.0.0.1:${port}`
  }

  async stop(): Promise<void> {
    this.server.closeAllConnections()
    await new Promise<void>((resolve) => this.server.close(() => resolve()))
  }

  reset(): void {
    this.entries = []
    this.accounts.clear()
    this.faults = []
    this.linesPerChunk = 3
    this.chunkDelayMs = 2
    this.requests.length = 0
    this.abandoned = 0
  }

  private async handle(req: IncomingMessage, res: ServerResponse): Promise<void> {
    const url = new URL(req.url ?? '/', 'http://localhost')
    if (url.pathname !== '/transactions/stream') return this.json(res, 404, { message: 'Not found' })

    const account = url.searchParams.get('account')
    const after = Number(url.searchParams.get('after') ?? '0')
    this.requests.push({ account, after })

    const lastSeq = this.entries.at(-1)?.seq ?? 0
    const known = (id: string) => this.accounts.has(id) || this.entries.some((e) => e.accountId === id)
    if (account && !known(account)) return this.json(res, 404, { message: `Account ${account} not found` })
    if (after > lastSeq) {
      return this.json(res, 409, { message: `after ${after} is beyond the end of the ledger (lastSeq ${lastSeq})` })
    }

    const fault = this.faults.shift()
    if (fault?.kind === 'busy') {
      return this.json(res, 503, { message: 'Too many transaction streams in progress' }, {
        'Retry-After': String(fault.retryAfterSeconds)
      })
    }

    const matching = this.entries.filter((e) => e.seq > after && (!account || e.accountId === account))
    res.on('close', () => {
      if (!res.writableFinished) this.abandoned += 1
    })
    res.writeHead(200, { 'Content-Type': 'application/x-ndjson', 'Access-Control-Allow-Origin': '*' })
    res.flushHeaders()

    let sent = 0
    let lastSent = after
    for (let i = 0; i < matching.length; i += this.linesPerChunk) {
      if (res.destroyed) return
      let chunk = matching.slice(i, i + this.linesPerChunk)
      const cutHere = fault?.kind === 'cut' && sent + chunk.length >= fault.afterEntries
      if (cutHere) chunk = chunk.slice(0, fault.afterEntries - sent)
      res.write(chunk.map((e) => JSON.stringify(e) + '\n').join(''))
      sent += chunk.length
      lastSent = chunk.at(-1)?.seq ?? lastSent
      if (cutHere) {
        await delay(this.chunkDelayMs)
        res.destroy() // no end line, connection gone
        return
      }
      await delay(this.chunkDelayMs)
    }
    res.end(JSON.stringify({ end: true, count: sent, lastSeq: lastSent }) + '\n')
  }

  private json(res: ServerResponse, status: number, body: unknown, headers: Record<string, string> = {}): void {
    res.writeHead(status, { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*', ...headers })
    res.end(JSON.stringify(body))
  }
}
