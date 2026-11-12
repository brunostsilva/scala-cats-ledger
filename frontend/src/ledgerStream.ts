import type { Transaction } from './api'
import { API_URL } from './config'

/** A transaction with its position in the ledger, as streamed by `GET /transactions/stream`. */
export type LedgerEntry = Transaction & { seq: number }

/** The last line of a complete stream. `lastSeq` is where to resume from (`after=lastSeq`). */
export type StreamEnd = { end: true; count: number; lastSeq: number }

export type StreamLedgerOptions = {
  /** Only this account's transactions. */
  account?: string
  /** Only transactions after this `seq`, to resume a stream. */
  after?: number
  pageSize?: number
  signal?: AbortSignal
  /** Called with the entries of each chunk, in order, as soon as it arrives. */
  onEntries: (entries: LedgerEntry[]) => void
}

/** The server refused the stream before sending anything (400, 404, 409, 503...). */
export class StreamRequestError extends Error {
  override readonly name = 'StreamRequestError'

  constructor(
    message: string,
    readonly status: number,
    /** From `Retry-After`, when the server sent one (with a 503). */
    readonly retryAfterSeconds: number | null = null
  ) {
    super(message)
  }
}

/**
 * The response ended without its `{"end":true,…}` line, or the connection broke: the stream was cut
 * short after it started. Resume with `after = lastSeq`.
 */
export class StreamCutShortError extends Error {
  override readonly name = 'StreamCutShortError'

  constructor(readonly lastSeq: number) {
    super(`The stream was cut short after #${lastSeq}`)
  }
}

/**
 * Streams the ledger (NDJSON, oldest first), handing each chunk's entries to `onEntries` as they
 * arrive instead of waiting for the whole response, and resolves with the end line. Lines can be
 * split anywhere across chunks; only complete lines are parsed.
 */
export async function streamLedger({ account, after = 0, pageSize, signal, onEntries }: StreamLedgerOptions): Promise<StreamEnd> {
  const params = new URLSearchParams()
  if (account) params.set('account', account)
  if (after > 0) params.set('after', String(after))
  if (pageSize) params.set('pageSize', String(pageSize))
  const query = params.toString() ? `?${params}` : ''

  const response = await fetch(`${API_URL}/transactions/stream${query}`, {
    headers: { Accept: 'application/x-ndjson' },
    signal
  })
  if (!response.ok) throw await requestError(response)
  if (!response.body) throw new Error('The stream response has no body')

  const reader = response.body.getReader()
  // Cancelling the body ends the read loop right away, whether or not the fetch implementation
  // still honours the signal once the response has arrived.
  const cancel = () => void reader.cancel().catch(() => undefined)
  signal?.addEventListener('abort', cancel, { once: true })

  const decoder = new TextDecoder()
  const progress: { lastSeq: number; end: StreamEnd | null } = { lastSeq: after, end: null }
  let pending = ''

  const parse = (lines: string[]) => {
    const entries: LedgerEntry[] = []
    for (const line of lines) {
      if (progress.end || line.trim() === '') continue
      const value = JSON.parse(line) as LedgerEntry | StreamEnd
      if ('end' in value) {
        progress.end = value
      } else {
        entries.push(value)
        progress.lastSeq = value.seq
      }
    }
    if (entries.length > 0) onEntries(entries)
  }

  try {
    for (;;) {
      let chunk: ReadableStreamReadResult<Uint8Array>
      try {
        chunk = await reader.read()
      } catch {
        if (signal?.aborted) throw signal.reason
        // The connection broke mid-stream (reset, proxy timeout...): resumable like a missing end line.
        throw new StreamCutShortError(progress.lastSeq)
      }
      if (signal?.aborted) throw signal.reason
      if (chunk.done) break
      pending += decoder.decode(chunk.value, { stream: true })
      const lines = pending.split('\n')
      pending = lines.pop() ?? ''
      parse(lines)
    }
    // A last line without its newline is either the end line written without one, or a line the
    // connection cut in half: the fragment is dropped, and the missing end line reports the cut.
    try {
      parse([pending + decoder.decode()])
    } catch (error) {
      if (!(error instanceof SyntaxError)) throw error
    }
  } catch (error) {
    // Whatever stopped the reading (a malformed line, a failing callback...), the rest of the body
    // mustn't keep streaming into a reader nobody drains: the connection would stay open and hold one
    // of the server's concurrent stream slots until the server finished on its own.
    cancel()
    throw error
  } finally {
    signal?.removeEventListener('abort', cancel)
  }

  if (!progress.end) throw new StreamCutShortError(progress.lastSeq)
  return progress.end
}

async function requestError(response: Response): Promise<StreamRequestError> {
  const body = (await response.json().catch(() => null)) as { message?: string } | null
  const retryAfter = response.headers.get('Retry-After')
  const seconds = retryAfter === null ? null : Number(retryAfter)
  return new StreamRequestError(
    body?.message ?? (response.statusText || 'Request failed'),
    response.status,
    seconds !== null && Number.isFinite(seconds) && seconds >= 0 ? seconds : null
  )
}
