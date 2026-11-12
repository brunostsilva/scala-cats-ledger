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
— just enough to exercise the API end to end — plus a live view of the streamed ledger (see
[Streaming the ledger in the UI](#streaming-the-ledger-in-the-ui)).

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
- **Ledger streaming:** ledger order and consecutive `seq`, paging, the account filter, `after`,
  the snapshot bound, rejected operations staying out of the ledger and concurrent transfers
  recorded exactly once (`TransactionStreamSpec`); the NDJSON output, `StreamEnd` line, parameter
  validation (400/404 before any streaming), the stream limit (503), an endless ledger read lazily,
  a failure mid-stream and a client disconnecting over a real socket, with the storage cursor
  released in every case (`TransactionStreamRoutesSpec`); `PageSize`, the stream settings, and the
  endpoint on the full app (`LedgerServerSpec`).
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
mocked API. It needs Node 22.22+ or 24.15+. For the ledger stream:

- **`ledgerStream.test.ts`:** the NDJSON client on its own: lines split anywhere across chunks
  (including mid-line and inside the end line), entries handed over chunk by chunk, an end line
  without a trailing newline, the query string, a stream cut short (where to resume from), refused
  streams with their status, message and `Retry-After`, and aborting mid-stream.
- **`LedgerStream.integration.test.tsx`:** the component, its hook and the client together, **over
  real HTTP**, against a fake backend (`src/test/fakeLedgerServer.ts`, a Node HTTP server
  implementing the documented contract) that writes NDJSON in small chunks: the whole ledger and
  one account's history; a connection dropped mid-stream, resumed from the last `seq` with nothing
  lost or duplicated; a `503` waited out with `Retry-After`; following new transactions; a `409`
  after the ledger was reset, streamed again from the start; stopping, which closes the connection;
  a refused stream's message; and a 1,200-entry ledger with only the latest rows kept.

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
- **When the access log line is written.** A body built in memory (e.g. a JSON reply) is logged as
  soon as the route returns it, so the request is logged even if the server never gets to send it.
  A streamed body (`GET /transactions/stream`) is logged when it ends, with its real duration, and
  `http.response.outcome` says how it ended: `completed`, `failed` (after the status was sent, at
  `WARN`) or `interrupted` (the connection went away, at `INFO`). A client leaving mid-stream is only
  noticed when the next write fails with a connection error (broken pipe, connection reset), which is
  why connection errors count as `interrupted`, not `failed`. A stream whose headers never reach the
  client leaves no line: http4s has no hook for it.
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
| GET    | `/transactions/stream`          | —                                       | Stream the ledger as NDJSON        |

### Streaming the ledger

`GET /transactions/stream` returns the ledger as [NDJSON](https://github.com/ndjson/ndjson-spec),
oldest first: every transaction of every account (deposits and both legs of each transfer), one
JSON object per line, in the same shape as `GET /accounts/{id}/transactions` plus its position in
the ledger, `seq`. A last line marks the end of the stream:

```bash
curl -N "localhost:8080/transactions/stream"
{"seq":1,"id":"5b0d…","accountId":"6f1c…","counterpartyId":null,"txType":"Deposit","amount":100.00,"balanceAfter":100.00,"occurredAt":"2026-11-09T14:02:11.481Z"}
{"seq":2,"id":"0c7e…","accountId":"6f1c…","counterpartyId":"9a2e…","txType":"Transfer","amount":2.50,"balanceAfter":97.50,"occurredAt":"2026-11-09T14:02:12.107Z"}
{"seq":3,"id":"0c7e…","accountId":"9a2e…","counterpartyId":"6f1c…","txType":"Transfer","amount":2.50,"balanceAfter":2.50,"occurredAt":"2026-11-09T14:02:12.107Z"}
{"end":true,"count":3,"lastSeq":3}
```

| Query parameter | Default | Description |
|---|---|---|
| `account` | all accounts | Only this account's transactions: its history, oldest first |
| `after` | `0` | Only transactions with a greater `seq`, to resume a stream |
| `pageSize` | `500` | Transactions read from storage at a time, 1 to 1000 |

- **Complete or cut short:** the `{"end":true,…}` line is only written when the stream completes. A
  response without it was cut short (e.g. a storage failure after the `200` was sent), and the
  client can resume with `after` set to the last `seq` it received. `lastSeq` is where to resume
  from after a complete stream, so a client can poll for new transactions with `after=<lastSeq>`.
- **Errors before streaming:** invalid parameters are a `400`, an unknown account a `404`, an
  `after` beyond the end of the ledger a `409` (see below), and while the limit of concurrent
  streams is running a `503` with `Retry-After`. They are all decided before the `200`, since a
  stream can't change its status once it has started.
- **A cursor beyond the end:** `seq` starts again at 1 when this in-memory ledger restarts, so an
  `after` saved before a restart can point past the end. Streaming nothing and echoing it back would
  make the client silently skip every new transaction, so it's a `409` telling the client to restart
  from `after=0`. An `after` equal to the last `seq` is the normal "nothing new yet" poll.
- **Concurrent stream limit:** only streams that actually started count: the count goes up when the
  response body starts and down when it ends. Reserving a slot before the `200` would leak it
  whenever the body never starts (e.g. the server fails to write the headers to a client that
  already left), and enough leaks would lock the endpoint. The trade-off is a soft limit: requests
  arriving at the same moment can briefly run a few streams over it.
- **Memory:** the ledger is never collected into a list. `LedgerService.streamTransactions` reads
  storage one page at a time (keyset pagination by `seq` over the append-only log) and each page is
  encoded and written before the next one is read. The storage is in memory in this project; with
  a database the same `Stream` would come from a cursor over
  `WHERE seq > :after ORDER BY seq LIMIT :pageSize` (e.g. Doobie's `query.stream`).
- **Backpressure:** fs2 only reads the next page when the client has taken the previous one, so a
  slow client slows the reads down instead of piling data up on the server.
- **Consistency:** a stream covers the transactions that existed when it started; later ones are
  picked up by the next request with `after`.
- **Resource safety:** if the client disconnects or the stream fails, http4s interrupts the body
  stream and its finalizers run, so the stream stops counting against the limit and any storage
  cursor acquired with `Stream.bracket`/`Stream.resource` is released.
- **Configuration:** `TRANSACTION_STREAM_PAGE_SIZE` (default page size, 1 to 1000, default `500`) and
  `TRANSACTION_STREAM_MAX_CONCURRENT` (streams running at once, default `8`). Invalid values stop
  the app at startup with a message.

### Streaming the ledger in the UI

The **Ledger stream** panel streams the whole ledger, or one account's history, and can keep
following new transactions:

- **Incremental reading** (`src/ledgerStream.ts`): the response body is read with a
  `ReadableStream` reader and decoded as it arrives; only complete lines are parsed, and each
  chunk's entries are rendered right away instead of waiting for the whole response.
- **The contract, client side** (`src/useLedgerStream.ts`):
  - a response without its end line, or a connection that breaks, is resumed with `after` set to
    the last `seq` received, so nothing is lost or shown twice (up to 3 times in a row);
  - a `503` is retried after `Retry-After`;
  - a `409` (the cursor is beyond the end because the in-memory ledger restarted) streams the
    ledger again from `after=0`;
  - **Follow** polls with `after=<lastSeq>` every 2 seconds once the stream is complete, so only new
    transactions travel.
- **Bounded memory:** the panel keeps the latest 500 rows and counts the rest, so a ledger of any
  size can be streamed in the browser.
- **Cancellation:** **Stop**, starting again or leaving the page aborts the request and cancels the
  body, which closes the connection, so the backend's stream ends and stops counting against its
  limit.
- **Accessibility:** the progress is a polite live region (`role="status"`) and a failure an
  `alert`.

## Tech stack

Scala 3.10 · JDK 25 · Cats Effect 3 · fs2 · http4s · circe · log4cats · Logback · munit-cats-effect · React 19 · TypeScript · Vite 8 ·
Vitest · Testing Library · Streams API (NDJSON)
