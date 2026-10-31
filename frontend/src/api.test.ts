import { describe, expect, it, vi } from 'vitest'
import { api } from './api'
import { alice, bob, transaction } from './test/fixtures'

const API_URL = 'http://localhost:8080'

function stubFetch(response: Response) {
  const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(response)
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function json(body: unknown, init: ResponseInit = {}): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init
  })
}

function sentBody(fetchMock: ReturnType<typeof stubFetch>): unknown {
  const init = fetchMock.mock.calls[0][1]
  return JSON.parse(String(init?.body))
}

describe('api', () => {
  it('creates an account with a JSON POST to /accounts', async () => {
    const fetchMock = stubFetch(json(alice))

    await expect(api.createAccount('Alice')).resolves.toEqual(alice)

    expect(fetchMock).toHaveBeenCalledWith(`${API_URL}/accounts`, expect.objectContaining({ method: 'POST' }))
    expect(fetchMock.mock.calls[0][1]?.headers).toEqual({ 'Content-Type': 'application/json' })
    expect(sentBody(fetchMock)).toEqual({ owner: 'Alice' })
  })

  it('fetches a single account by id', async () => {
    const fetchMock = stubFetch(json(bob))

    await expect(api.getAccount(bob.id)).resolves.toEqual(bob)

    expect(fetchMock).toHaveBeenCalledWith(`${API_URL}/accounts/${bob.id}`)
  })

  it('deposits by POSTing the amount to the account', async () => {
    const fetchMock = stubFetch(json({ ...alice, balance: 25.5 }))

    await expect(api.deposit(alice.id, 25.5)).resolves.toEqual({ ...alice, balance: 25.5 })

    expect(fetchMock).toHaveBeenCalledWith(
      `${API_URL}/accounts/${alice.id}/deposit`,
      expect.objectContaining({ method: 'POST' })
    )
    expect(sentBody(fetchMock)).toEqual({ amount: 25.5 })
  })

  it('transfers by POSTing from, to and amount to /transfers', async () => {
    const result = { from: { ...alice, balance: 70 }, to: { ...bob, balance: 80 } }
    const fetchMock = stubFetch(json(result))

    await expect(api.transfer(alice.id, bob.id, 30)).resolves.toEqual(result)

    expect(fetchMock).toHaveBeenCalledWith(`${API_URL}/transfers`, expect.objectContaining({ method: 'POST' }))
    expect(sentBody(fetchMock)).toEqual({ from: alice.id, to: bob.id, amount: 30 })
  })

  it('lists an account transactions', async () => {
    const txs = [transaction(), transaction({ id: 'tx-2', txType: 'Transfer', counterpartyId: bob.id })]
    const fetchMock = stubFetch(json(txs))

    await expect(api.transactions(alice.id)).resolves.toEqual(txs)

    expect(fetchMock).toHaveBeenCalledWith(`${API_URL}/accounts/${alice.id}/transactions`)
  })

  it('surfaces the message from a JSON error response', async () => {
    stubFetch(json({ message: 'Insufficient funds' }, { status: 422 }))

    await expect(api.transfer(alice.id, bob.id, 1000)).rejects.toThrow('Insufficient funds')
  })

  it('falls back to the status text when the error body is not JSON', async () => {
    stubFetch(new Response('upstream exploded', { status: 502, statusText: 'Bad Gateway' }))

    await expect(api.getAccount(alice.id)).rejects.toThrow('Bad Gateway')
  })

  it('uses a generic message when a JSON error has no message', async () => {
    stubFetch(json({ code: 'E42' }, { status: 500 }))

    await expect(api.deposit(alice.id, 10)).rejects.toThrow('Request failed')
  })
})
