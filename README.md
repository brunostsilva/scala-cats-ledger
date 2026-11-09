# Mini Ledger

A small, purely functional ledger service built with **Scala 3** and **Cats Effect**, paired with a
**React** frontend. It lets you create accounts, deposit funds, transfer money between accounts, and
inspect a per-account transaction history.

This project is intentionally small in scope — it exists to demonstrate a clean, idiomatic approach to
functional backend design (effect types, immutable domain modeling, typed errors, concurrency-safe
state) rather than to be a production payments system.

## Architecture

```
backend/   Scala 3 + Cats Effect + http4s + circe
frontend/  React + TypeScript + Vite
```

**Domain** (`ledger.domain`)
Immutable value types modeling the business rules: `Money` (stored as integer cents to avoid
floating-point rounding issues), `Account`, `Transaction`, and a closed `LedgerError` ADT for every way
an operation can fail (account not found, insufficient funds, self-transfer).

**Service** (`ledger.service`)
`LedgerService` holds all business logic. The in-memory implementation keeps the entire ledger state in
a single `cats.effect.Ref`, so every mutation (deposit, transfer) is applied atomically via `Ref.modify`
— no locks, no race conditions, even under concurrent requests. `AuditedLedgerService` decorates it
with an audit trail (see [Logging](#logging)), so the business logic itself never logs.

**HTTP** (`ledger.http`)
An `http4s` API exposing the service over JSON (`circe`). Money crosses the wire as a decimal amount
(e.g. `10.50`) for a friendlier contract, while staying integer cents internally. Every request gets a
request id and one access log line.

**Frontend** (`frontend/`)
A minimal React UI to create accounts, deposit, transfer between accounts, and view transaction history
— just enough to exercise the API end to end.

## Running locally

### Backend

Requires **JDK 25** (Temurin recommended) and sbt; the build uses Scala 3.10.

```bash
cd backend
sbt run
```

The API starts on `http://localhost:8080`.

### Frontend

```bash
cd frontend
npm install
npm run dev
```

The UI starts on `http://localhost:5173` and talks to the backend at `http://localhost:8080` by default
(override with `VITE_API_URL`).

### Tests

```bash
cd backend
sbt test
```

The backend suite (munit + munit-cats-effect) covers:

- **Domain:** `Money` (cents, half-up rounding of decimals, range limits, overflow-safe addition,
  no negative balances) and `AccountId` parsing.
- **JSON codecs:** the wire format of every type, including money as a decimal amount and
  validation errors.
- **HTTP API end to end:** every route with its success and error statuses (201/200/400/404/422)
  and messages, running the real routes over the real in-memory service.
- **Real server:** `LedgerServerSpec` starts the same wiring as `Main` (`LedgerServer.resource`) on a
  free port and calls it with an HTTP client: deposits and transfers through routes, service and
  audit, request ids and CORS headers in the responses, and the matching log lines. No Docker needed.
- **Real logging stack:** `RealLoggingSpec` runs the server with the same logging as `Main`
  (log4cats → SLF4J → Logback with `logback.xml`), so its log lines are printed in the `sbt test`
  output, in CI too, and checks what Logback received: every layer, structured fields in the MDC,
  no owner names. The other tests record log lines in memory instead.
- **Transfer streaming:** paging, order and snapshot bound of `streamTransfers`
  (`TransferStreamSpec`); the NDJSON output and headers, an endless ledger read lazily, a failure
  mid-stream, and a client disconnecting over a real socket, with the storage cursor released in
  every case (`TransferStreamRoutesSpec`); and the endpoint on the full app (`LedgerServerSpec`).
- **Service:** deposits, transfers, transaction history, and **concurrency**: parallel deposits are
  all applied, and parallel transfers never overdraw an account, since every mutation is an atomic
  `Ref` update.
- **Logging:** the audit trail (events, ids, amounts, reason codes, and no owner names or balances)
  and the access log (request id reused or generated, no query strings or bodies, exceptions
  logged as 500s) with log4cats' `StructuredTestingLogger`, plus the Logback configuration in
  text and JSON mode.

```bash
cd frontend
npm test          # Vitest + Testing Library (jsdom), once
npm run test:watch
```

The frontend suite covers the API client (URLs, payloads and error handling, with `fetch` stubbed),
every component, and the `App` flows (create, deposit, transfer, select, error banner) against a
mocked API. It needs Node 22.22+ or 24.15+.

## Logging

Logging uses [log4cats](https://typelevel.org/log4cats/), so a log call is an effect (`F[Unit]`)
composed like any other, over SLF4J with **Logback** as the backend. Every line comes from one of
these loggers:

| Logger | What | Example |
|---|---|---|
| `ledger.audit` | Every change to the ledger, and every rejected one | `Transfer of 2.00 from account 6f1c… to 9a2e… rejected: insufficient_funds` |
| `ledger.service` | One line when a deposit or transfer starts | `Creating transfer transaction from account 6f1c… to 9a2e…` |
| `ledger.http.access` | One line per HTTP request | `POST /transfers -> 422 in 3 ms [5b0d3c1e-…]` |
| `ledger.server` | Server lifecycle (Ember) | `Ember-Server service bound to address: [::]:8080` |

- **Audit trail as a decorator.** `AuditedLedgerService` wraps the service and logs after each
  operation, with a structured context: `event` (`account.created`, `deposit.completed`,
  `transfer.rejected`…), account ids, `amount` and a stable `reason` code
  (`insufficient_funds`, `account_not_found`…). The business logic stays pure, and the decorator
  is wired in `Main`.
- **Request ids.** http4s' `RequestId` middleware reuses the caller's `X-Request-ID` header or
  generates one, adds it to the access log line and returns it in the response, so a support
  ticket can quote it.
- **No personal data.** Owner names, balances, request bodies, headers and query strings are
  never logged. The tests assert it.

**JSON output.** By default the console shows readable text. `LOG_FORMAT=json` switches to one JSON
object per line (Logback's built-in `JsonEncoder`, no extra dependency), with the structured context
under `mdc` (example abridged):

```bash
LOG_FORMAT=json sbt run
```

```json
{"timestamp":1794312000123,"level":"INFO","loggerName":"ledger.audit","mdc":{"event":"deposit.completed","account.id":"6f1c…","amount":"10.50"},"formattedMessage":"Deposit of 10.50 into account 6f1c…"}
```

Only `text` and `json` are accepted: any other value (`JSON`, `plain`...) would leave Logback with no
appender and silently drop every line, so the app refuses to start and says why. `LOG_LEVEL` changes
the root level (`INFO` by default), e.g. `LOG_LEVEL=DEBUG sbt run`.

**Non-blocking writes.** log4cats runs each log call synchronously on the Cats Effect compute pool, so
the console appenders sit behind Logback `AsyncAppender`s: a log call only enqueues the event, and a
background thread writes it. The queues are configured never to drop events (the default would drop
INFO lines, i.e. the audit trail, when nearly full); a full queue makes callers wait instead, and the
queues are flushed on shutdown.

**Audit guarantees.** Each change and its audit line run as one uncancelable step, so a request
cancelled halfway (e.g. a client disconnecting) can't commit a change without recording it. A failing
logger never fails a committed change, since turning it into a 500 would invite a duplicate retry.

## API

| Method | Path                           | Body                                  | Description                       |
| ------ | ------------------------------ | -------------------------------------- | ---------------------------------- |
| POST   | `/accounts`                    | `{ "owner": string }`                  | Create an account                  |
| GET    | `/accounts/{id}`                | —                                       | Fetch an account                   |
| POST   | `/accounts/{id}/deposit`        | `{ "amount": number }`                 | Deposit into an account            |
| POST   | `/transfers`                    | `{ "from", "to", "amount": number }`   | Transfer between two accounts      |
| GET    | `/accounts/{id}/transactions`   | —                                       | List an account's transaction log  |
| GET    | `/stream/transfers`             | —                                       | Stream every transfer as NDJSON    |

### Streaming transfers

`GET /stream/transfers` returns every completed transfer, oldest first, as
[NDJSON](https://github.com/ndjson/ndjson-spec): one JSON object per line, with
`Content-Type: application/x-ndjson` and `Transfer-Encoding: chunked`.

```bash
curl -N localhost:8080/stream/transfers
{"id":"0c7e…","from":"6f1c…","to":"9a2e…","amount":2.00,"occurredAt":"2026-11-09T14:02:11.481Z"}
{"id":"5b0d…","from":"9a2e…","to":"6f1c…","amount":1.00,"occurredAt":"2026-11-09T14:02:12.107Z"}
```

- **Memory:** the ledger is never collected into a list. `LedgerService.streamTransfers` reads
  storage in pages of 500 (`Stream.unfoldChunkEval` over the append-only transfer log), and each page
  is encoded and written before the next one is read. The storage is in memory in this project; with
  a database the same `Stream[F, Transfer]` would come from a cursor (e.g. Doobie's `query.stream`).
- **Backpressure:** fs2 only reads the next page when the client has taken the previous one, so a
  slow client slows the reads down instead of piling data up on the server.
- **Consistency:** the stream covers the transfers that existed when it started; transfers made
  while it runs appear in the next request.
- **Resource safety:** if the client disconnects or the stream fails, http4s interrupts the body
  stream and its finalizers run, so a storage cursor acquired with `Stream.bracket`/`Stream.resource`
  is always released. A failure after the first lines can only cut the response short, since the
  `200` was already sent.

## Tech stack

Scala 3.10 · JDK 25 · Cats Effect 3 · fs2 · http4s · circe · log4cats · Logback · munit-cats-effect · React 19 · TypeScript · Vite 8 ·
Vitest · Testing Library
