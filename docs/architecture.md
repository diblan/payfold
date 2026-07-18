# Architecture

System map for Payfold. Read this before touching the message flow, schema, or config.
When behavior changes, this file changes in the same PR ([G6](invariants.md#g6)).

## What Payfold demonstrates — and what it doesn't (yet)

The claim is "a system that can bill 10M subscription renewals a month".
10M/month ≈ 330k/day ≈ 3.8/sec sustained — but renewals are billed in a daily batch,
so the real requirement is: scan and publish ~330k due renewals in one nightly run,
and consume them at a rate that drains the queue well before the next run.

Honesty table:

| Property | Status |
|---|---|
| Transactional outbox (atomic scan + publish intent) | Demonstrated |
| Idempotent redelivery handling | Partially — same-day only, see [G2](invariants.md#g2) → [R4](roadmap.md#r4) |
| Bounded failure handling (DLQ) | Declared, not reachable — see [G5](invariants.md#g5) → [R5](roadmap.md#r5) |
| Throughput at 330k/day | Extrapolated — demo seeds 15k; measured run is [R12](roadmap.md#r12) |
| Horizontal producer scaling | Not yet — no `SKIP LOCKED`, see [R7](roadmap.md#r7) |

## Component map

```
                 ┌─────────────┐   Flyway V1–V4    ┌──────────────┐
                 │   flyway    ├──────────────────▶│              │
                 └─────────────┘                   │  postgres:18 │
                 ┌─────────────┐   15k customers   │   (payfold)  │
                 │  seed-data  ├──────────────────▶│              │
                 └─────────────┘                   └──────┬───────┘
                                                          │
   cron 03:00 ──▶┌──────────────────────┐    scanStep     │
   or POST       │   renewal-producer   │  INSERT..SELECT │
   /actuator/    │   (Spring Batch)     ├────────────────▶│  renewal_outbox
   renewal-job   │   :8080              │    publishStep  │
                 └──────────┬───────────┘  page 1000/tx   │
                            │ publish + mark published_at │
                            ▼                             │
                 ┌──────────────────────┐                 │
                 │ RabbitMQ 3.13        │                 │
                 │ exchange:            │                 │
                 │  billing.renewals    │                 │
                 │  rk renewal.requested│                 │
                 │  └▶ queue            │                 │
                 │     billing.renewals │                 │
                 │     .main            │                 │
                 │                      │                 │
                 │ DLX billing.renewals │                 │
                 │ .dlx ─▶ DLQ (rk dlq) │                 │
                 │ [declared, currently │                 │
                 │  UNREACHABLE → R5]   │                 │
                 └──────────┬───────────┘                 │
                            ▼                             ▼
                 ┌──────────────────────┐   invoice / charge /
                 │   renewal-consumer   ├─▶ payment / subscription
                 │   :8081 (host)       │   upserts via unique
                 └──────────────────────┘   constraints
```

## The renewal job (producer)

Job `renewalJob` = `scanStep` → `publishStep`, defined in
`billing-engine/renewal-producer/.../job/RenewalJobConfig.java`.

Two ways to launch, both build the identifying parameter `scheduleDate = today`:

- **Cron** — `RenewalScheduler`, `${app.scheduleCron}` (default `0 0 3 * * *`, zone `${app.timezone}`).
- **On demand** — `POST /actuator/renewal-job?force=true` (`RenewalJobEndpoint`).
  `force=true` adds a random `run.id` so the same day can be re-run; without it,
  Spring Batch rejects a repeated job instance for the same `scheduleDate`.
  The endpoint is **synchronous**: it blocks until the whole job finishes ([R10](roadmap.md#r10)).

**scanStep** — one tasklet, one transaction, one SQL statement:
`INSERT INTO renewal_outbox (subscription_id, due_date, payload) SELECT ...` over active
subscriptions whose `renewed_at + plan interval` falls in today's local-day window.
`ON CONFLICT (subscription_id, due_date) DO NOTHING` (constraint `uniq_outbox_sub_due`)
makes re-scans idempotent. Payload is `jsonb_build_object(subscription_id, customer_id,
plan_id, interval, amount_cents, currency)` — note it does **not** carry
`idempotency_key`, `due_date`, or the billing period yet ([R4](roadmap.md#r4)).
Single transaction over all active subscriptions is the 10M-scale bottleneck ([R11](roadmap.md#r11)).

**publishStep** — tasklet re-run per page (`RepeatStatus.CONTINUABLE`), one page per
transaction: select `LIMIT ${app.publishPageSize}` (default 1000) unpublished rows ordered
by `id`, publish each via `OutboxPublisher` (plain `convertAndSend`, no publisher confirms
→ [R6](roadmap.md#r6), no `FOR UPDATE SKIP LOCKED` → [R7](roadmap.md#r7)), then batch-update
`published_at = now()`.

Producer declares only the exchange (`RabbitConfig`); the consumer owns the rest of the topology.

## The consumer

`payment-service/renewal-consumer/.../mq/RenewalListener.java` listens on
`billing.renewals.main`, deserializes to the `RenewalRequested` record, and calls
`BillingService.process`, which runs an upsert chain — each step backed by a DB
unique constraint (this *is* the idempotency mechanism, [D2](decisions.md#d2)):

| Step | Table | Backing constraint |
|---|---|---|
| `upsertInvoice` | `invoice` | `uniq_invoice_period (customer_id, period_start, period_end, currency)` |
| `upsertCharge` | `charge` | `uniq_charge_period (subscription_id, due_date, amount_cents, currency)` |
| `upsertPayment` | `payment` | `payment.idempotency_key UNIQUE` |
| `markPaymentSucceeded` | `payment` | — (unconditional; no PSP call yet → [R8](roadmap.md#r8)) |
| `finalizeBilling` | `charge`, `invoice`, `subscription` | sets settled/paid, advances `renewed_at` |

**Known flaw** ([G2](invariants.md#g2), fixed by [R4](roadmap.md#r4)): because the payload
lacks a key and period, the consumer derives both from `LocalDate.now()` at consume time.
A redelivery after midnight gets a *different* key and period → duplicate
invoice/charge/payment. Same-day redelivery is safe; any-time redelivery is not.

**Topology** (`RabbitTopology`): main queue has `x-dead-letter-exchange:
billing.renewals.dlx`, but **no `x-dead-letter-routing-key`** — dead letters would keep
rk `renewal.requested` while the DLQ is bound with rk `dlq`, so they'd be *dropped*.
Moreover there is no `spring.rabbitmq.listener` retry config, so failed messages requeue
forever and never dead-letter at all. Both halves are [R5](roadmap.md#r5).

## Data model (Flyway, `db-migrations/`)

| Migration | Contents |
|---|---|
| V1 | `customer`, `mandate`, `payment_method`, `plan`, `subscription`, `invoice`, `charge`, `payment` — plus `bank_tx`, `recon_match`, `ledger_entry`, which **no code touches**; reconciliation/ledger is a non-goal until promoted ([roadmap](roadmap.md#non-goals)) |
| V2 | 3 `plan` rows (Basic/Standard/Premium, cents + EUR, monthly) |
| V3 | `renewal_outbox` + the unique constraints in the table above + supporting indexes |
| V4 | Spring Batch 5 metadata schema (producer sets `spring.batch.jdbc.initialize-schema: never`; Flyway is the sole schema authority, [G3](invariants.md#g3)) |

`renewal_outbox`: `id, subscription_id, due_date, payload jsonb, created_at, published_at`.
Unpublished = `published_at IS NULL`.

## Configuration truth table

Every key's real consumer. "DEAD" keys are deleted by [R2](roadmap.md#r2); keep this
table at all-alive after that.

| Key (application.yaml) | Consumed by | Status |
|---|---|---|
| `spring.datasource.*` | Spring Boot autoconfig (overridden by compose `SPRING_DATASOURCE_*`) | alive (placeholder values in yaml) |
| `spring.batch.jdbc.initialize-schema` (producer) | Spring Batch | alive |
| `app.timezone`, `app.scheduleCron`, `app.publishPageSize` (producer) | `RenewalScheduler`, `RenewalJobConfig` | alive |
| `rabbitmq.exchange`, `rabbitmq.routingKey` (producer) | `RabbitConfig`, `OutboxPublisher` | alive |
| `rabbitmq.host/port/username/password` (both) | **nothing** — broker connection comes from `spring.rabbitmq.*` compose env only | DEAD → R2 |
| `rabbitmq.exchange/queue/routingKey` (consumer) | `RabbitTopology`, `RenewalListener` | alive |
| `payment.provider.baseUrl/timeoutMillis` (consumer) | **nothing** — revived by [R8](roadmap.md#r8) | DEAD → R2 |
| `management.endpoints.*` (producer) | actuator exposure incl. `renewal-job` | alive |

Compose quirks (fixed by [R2](roadmap.md#r2)): the producer's app-specific env vars use
**dotted** names (`RABBITMQ.EXCHANGE`, `APP.TIMEZONE`) — non-standard for shell env and
only accidentally harmless because the yaml defaults equal the `.env` values. The
consumer correctly uses underscores. The consumer's compose healthcheck hits
`/actuator/health`, served by actuator since [R1](roadmap.md#r1).

## Ports & endpoints

| Where | What |
|---|---|
| `localhost:8080` | producer — `/actuator/health`, `POST /actuator/renewal-job?force=true` |
| `localhost:8081` | consumer — `/actuator/health` (since [R1](roadmap.md#r1)); container-internal 8080 |
| `localhost:5672` / `15672` | RabbitMQ AMQP / management UI (creds from `.env`) |
| `localhost:5432` | Postgres (creds from `.env`) |
