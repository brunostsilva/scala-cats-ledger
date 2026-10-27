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
— no locks, no race conditions, even under concurrent requests.

**HTTP** (`ledger.http`)
An `http4s` API exposing the service over JSON (`circe`). Money crosses the wire as a decimal amount
(e.g. `10.50`) for a friendlier contract, while staying integer cents internally.

**Frontend** (`frontend/`)
A minimal React UI to create accounts, deposit, transfer between accounts, and view transaction history
— just enough to exercise the API end to end.

## Running locally

### Backend

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

## API

| Method | Path                           | Body                                  | Description                       |
| ------ | ------------------------------ | -------------------------------------- | ---------------------------------- |
| POST   | `/accounts`                    | `{ "owner": string }`                  | Create an account                  |
| GET    | `/accounts/{id}`                | —                                       | Fetch an account                   |
| POST   | `/accounts/{id}/deposit`        | `{ "amount": number }`                 | Deposit into an account            |
| POST   | `/transfers`                    | `{ "from", "to", "amount": number }`   | Transfer between two accounts      |
| GET    | `/accounts/{id}/transactions`   | —                                       | List an account's transaction log  |

## Tech stack

Scala 3 · Cats Effect 3 · http4s · circe · munit-cats-effect · React · TypeScript · Vite
