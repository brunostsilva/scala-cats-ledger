import { describe, expect, it, vi } from 'vitest'
import { API_URL } from './config'
import { StreamCutShortError, StreamRequestError, streamLedger, type LedgerEntry } from './ledgerStream'
import { ledgerEntry } from './test/fixtures'

/** An NDJSON response whose body arrives in exactly these chunks. */
function chunked(chunks: string[], init: ResponseInit = {}): Response {
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(Buffer.from(chunk))
      controller.close()
    }
  })
  return new Response(body, { status: 200, headers: { 'Content-Type': 'application/x-ndjson' }, ...init })
}

function stubFetch(response: Response) {
  const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(response)
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const line = (value: unknown) => JSON.stringify(value) + '\n'

/** Collects what `streamLedger` hands to `onEntries`, batch by batch. */
function collector() {
  const batches: LedgerEntry[][] = []
  return { batches, onEntries: (entries: LedgerEntry[]) => batches.push(entries) }
}

describe('streamLedger', () => {
  it('parses lines split anywhere across chunks, and resolves with the end line', async () => {
    const text = line(ledgerEntry(1)) + line(ledgerEntry(2)) + line(ledgerEntry(3)) + line({ end: true, count: 3, lastSeq: 3 })
    // Cut mid-line, right after a newline, and inside the end line.
    stubFetch(chunked([text.slice(0, 40), text.slice(40, text.indexOf('\n') + 1), text.slice(text.indexOf('\n') + 1, -10), text.slice(-10)]))
    const { batches, onEntries } = collector()

    const end = await streamLedger({ onEntries })

    expect(end).toEqual({ end: true, count: 3, lastSeq: 3 })
    expect(batches.flat().map((e) => e.seq)).toEqual([1, 2, 3])
    expect(batches.flat()[0]).toEqual(ledgerEntry(1))
  })

  it('hands over the entries of each chunk as soon as it arrives', async () => {
    stubFetch(chunked([line(ledgerEntry(1)) + line(ledgerEntry(2)), line(ledgerEntry(3)), line({ end: true, count: 3, lastSeq: 3 })]))
    const { batches, onEntries } = collector()

    await streamLedger({ onEntries })

    expect(batches.map((batch) => batch.map((e) => e.seq))).toEqual([[1, 2], [3]])
  })

  it('accepts an end line without a trailing newline', async () => {
    stubFetch(chunked([line(ledgerEntry(1)) + JSON.stringify({ end: true, count: 1, lastSeq: 1 })]))

    await expect(streamLedger({ onEntries: () => undefined })).resolves.toEqual({ end: true, count: 1, lastSeq: 1 })
  })

  it('asks for the account, the cursor and the page size, and nothing it was not given', async () => {
    const fetchMock = stubFetch(chunked([line({ end: true, count: 0, lastSeq: 0 })]))
    await streamLedger({ onEntries: () => undefined })
    expect(fetchMock.mock.calls[0][0]).toBe(`${API_URL}/transactions/stream`)

    fetchMock.mockResolvedValue(chunked([line({ end: true, count: 0, lastSeq: 7 })]))
    await streamLedger({ account: 'acc-bob', after: 7, pageSize: 100, onEntries: () => undefined })
    expect(fetchMock.mock.calls[1][0]).toBe(`${API_URL}/transactions/stream?account=acc-bob&after=7&pageSize=100`)
    expect(fetchMock.mock.calls[1][1]?.headers).toEqual({ Accept: 'application/x-ndjson' })
  })

  it('fails with the last seq received when the response ends without its end line', async () => {
    stubFetch(chunked([line(ledgerEntry(4)) + line(ledgerEntry(5)) + '{"seq":6,"id":"tx-6"']))

    const error = await streamLedger({ after: 3, onEntries: () => undefined }).catch((e: unknown) => e)

    expect(error).toBeInstanceOf(StreamCutShortError)
    expect((error as StreamCutShortError).lastSeq).toBe(5)
  })

  it('reports where to resume from as the cursor asked for when nothing arrived before the cut', async () => {
    stubFetch(chunked([]))

    const error = await streamLedger({ after: 12, onEntries: () => undefined }).catch((e: unknown) => e)

    expect((error as StreamCutShortError).lastSeq).toBe(12)
  })

  it('turns a refused stream into an error with its status and message', async () => {
    const json = (status: number, message: string, headers: Record<string, string> = {}) =>
      new Response(JSON.stringify({ message }), { status, headers: { 'Content-Type': 'application/json', ...headers } })

    stubFetch(json(404, 'Account acc-x not found'))
    await expect(streamLedger({ account: 'acc-x', onEntries: () => undefined })).rejects.toMatchObject({
      name: 'StreamRequestError',
      status: 404,
      message: 'Account acc-x not found',
      retryAfterSeconds: null
    })

    stubFetch(json(503, 'Too many transaction streams in progress', { 'Retry-After': '1' }))
    const busy = await streamLedger({ onEntries: () => undefined }).catch((e: unknown) => e)
    expect(busy).toBeInstanceOf(StreamRequestError)
    expect(busy).toMatchObject({ status: 503, retryAfterSeconds: 1 })
  })

  it('stops reading as soon as it is aborted', async () => {
    // One chunk, then the body stays open, like a slow server.
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(Buffer.from(line(ledgerEntry(1))))
      }
    })
    stubFetch(new Response(body, { status: 200 }))
    const controller = new AbortController()
    const { batches, onEntries } = collector()

    const streaming = streamLedger({ signal: controller.signal, onEntries })
    await vi.waitFor(() => expect(batches).toHaveLength(1))
    controller.abort()

    await expect(streaming).rejects.toBeDefined()
    expect(batches.flat().map((e) => e.seq)).toEqual([1])
  })
})
