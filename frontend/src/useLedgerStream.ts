import { useCallback, useEffect, useReducer, useRef } from 'react'
import { StreamCutShortError, StreamRequestError, streamLedger, type LedgerEntry, type StreamEnd } from './ledgerStream'

export type StreamStatus =
  | 'idle'
  | 'streaming'
  | 'complete'
  /** Complete, and polling for new transactions. */
  | 'following'
  /** The connection broke mid-stream: resuming from the last transaction received. */
  | 'resuming'
  /** The server is at its limit of concurrent streams: waiting for `Retry-After`. */
  | 'waiting'
  | 'stopped'
  | 'failed'

export type LedgerStreamState = {
  status: StreamStatus
  /** The latest entries received, at most `maxEntries`, oldest first. */
  entries: LedgerEntry[]
  /** How many entries were received, including those no longer kept. */
  received: number
  /** The `seq` of the last entry received, or the end line's: where a resumed stream starts. */
  lastSeq: number
  /** What's going on during a recovery, or why the stream failed. */
  message: string | null
}

export type LedgerStreamOptions = {
  /** Entries kept for display: a long ledger is counted, not held, in full. */
  maxEntries?: number
  /** How often to look for new transactions when following. */
  followIntervalMs?: number
  /** Base delay before resuming a stream that was cut short (multiplied by the attempt number). */
  resumeDelayMs?: number
  /** Attempts in a row to resume a cut stream or retry a busy server, before giving up. */
  maxRetries?: number
}

type Action =
  | { type: 'start' }
  | { type: 'entries'; entries: LedgerEntry[]; maxEntries: number }
  | { type: 'complete'; end: StreamEnd; follow: boolean }
  | { type: 'resuming'; lastSeq: number }
  | { type: 'waiting'; seconds: number }
  | { type: 'reset' }
  | { type: 'stopped' }
  | { type: 'failed'; message: string }

const initialState: LedgerStreamState = { status: 'idle', entries: [], received: 0, lastSeq: 0, message: null }

function reduce(state: LedgerStreamState, action: Action): LedgerStreamState {
  switch (action.type) {
    case 'start':
      return { ...initialState, status: 'streaming' }
    case 'entries': {
      const entries = [...state.entries, ...action.entries]
      return {
        ...state,
        status: state.status === 'following' ? 'following' : 'streaming',
        entries: entries.length > action.maxEntries ? entries.slice(entries.length - action.maxEntries) : entries,
        received: state.received + action.entries.length,
        lastSeq: action.entries[action.entries.length - 1].seq,
        message: null
      }
    }
    case 'complete':
      return { ...state, status: action.follow ? 'following' : 'complete', lastSeq: action.end.lastSeq, message: null }
    case 'resuming':
      return { ...state, status: 'resuming', message: `Connection lost after #${action.lastSeq}, resuming…` }
    case 'waiting':
      return { ...state, status: 'waiting', message: `The server is busy, retrying in ${action.seconds}s…` }
    case 'reset':
      return {
        ...initialState,
        status: 'streaming',
        message: 'The ledger was reset on the server: streaming it again from the start.'
      }
    case 'stopped':
      return { ...state, status: 'stopped', message: null }
    case 'failed':
      return { ...state, status: 'failed', message: action.message }
  }
}

const RUNNING: ReadonlySet<StreamStatus> = new Set(['streaming', 'following', 'resuming', 'waiting'])

export function isRunning(status: StreamStatus): boolean {
  return RUNNING.has(status)
}

/**
 * Streams the ledger (or one account) into state, following the backend's contract:
 *
 * - **Cut short** (no end line, or the connection broke): resumes from the last `seq` received, so
 *   nothing is lost or shown twice; up to `maxRetries` times in a row.
 * - **503** (too many streams): waits for `Retry-After`, then tries again.
 * - **409** (`after` beyond the end: the ledger was reset): starts over from the beginning.
 * - **Following:** once complete, asks for `after=lastSeq` every `followIntervalMs`.
 *
 * Starting again or unmounting aborts the stream in progress.
 */
export function useLedgerStream({
  maxEntries = 500,
  followIntervalMs = 2000,
  resumeDelayMs = 500,
  maxRetries = 3
}: LedgerStreamOptions = {}) {
  const [state, dispatch] = useReducer(reduce, initialState)
  const controller = useRef<AbortController | null>(null)

  const start = useCallback(
    (account: string | null, follow: boolean) => {
      controller.current?.abort()
      const current = new AbortController()
      controller.current = current
      dispatch({ type: 'start' })
      void run(account, follow, current.signal, dispatch, { maxEntries, followIntervalMs, resumeDelayMs, maxRetries })
    },
    [maxEntries, followIntervalMs, resumeDelayMs, maxRetries]
  )

  const stop = useCallback(() => {
    if (!controller.current) return
    controller.current.abort()
    controller.current = null
    dispatch({ type: 'stopped' })
  }, [])

  useEffect(() => () => controller.current?.abort(), [])

  return { ...state, running: isRunning(state.status), start, stop }
}

async function run(
  account: string | null,
  follow: boolean,
  signal: AbortSignal,
  dispatch: (action: Action) => void,
  settings: Required<LedgerStreamOptions>
): Promise<void> {
  let after = 0
  let retries = 0
  let restarted = false

  while (!signal.aborted) {
    try {
      const end = await streamLedger({
        account: account ?? undefined,
        after,
        signal,
        onEntries: (entries) => {
          after = entries[entries.length - 1].seq
          retries = 0 // progress: a later failure gets a fresh budget
          dispatch({ type: 'entries', entries, maxEntries: settings.maxEntries })
        }
      })
      after = end.lastSeq
      retries = 0
      restarted = false
      dispatch({ type: 'complete', end, follow })
      if (!follow) return
      await sleep(settings.followIntervalMs, signal)
    } catch (error) {
      if (signal.aborted) return
      if (error instanceof StreamCutShortError && retries < settings.maxRetries) {
        retries += 1
        after = error.lastSeq
        dispatch({ type: 'resuming', lastSeq: after })
        await sleep(settings.resumeDelayMs * retries, signal)
      } else if (error instanceof StreamRequestError && error.status === 503 && retries < settings.maxRetries) {
        retries += 1
        const seconds = error.retryAfterSeconds ?? 1
        dispatch({ type: 'waiting', seconds })
        await sleep(seconds * 1000, signal)
      } else if (error instanceof StreamRequestError && error.status === 409 && !restarted) {
        // Our cursor is beyond the end: the in-memory ledger restarted and its seq began again at 1.
        restarted = true
        after = 0
        dispatch({ type: 'reset' })
      } else {
        dispatch({ type: 'failed', message: error instanceof Error ? error.message : String(error) })
        return
      }
    }
  }
}

/** Resolves after `ms`, or as soon as `signal` aborts. */
function sleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    if (signal.aborted) return resolve()
    const done = () => {
      clearTimeout(timer)
      signal.removeEventListener('abort', done)
      resolve()
    }
    const timer = setTimeout(done, ms)
    signal.addEventListener('abort', done, { once: true })
  })
}
