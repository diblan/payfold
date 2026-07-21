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
| Broker-acknowledged publish (publisher confirms) | Demonstrated — confirm-gated `published_at`, unconfirmed-repick test; unroutable-message detection is [R15](roadmap.md#r15) |
| Idempotent redelivery handling | Demonstrated — payload-keyed; covered by a cross-midnight integration test, see [G2](invariants.md#g2) |
| Bounded failure handling (DLQ) | Demonstrated — bounded listener retry + DLQ routing; poison-path integration test and `verify.sh` probe; see [G5](invariants.md#g5) |
| Provider failure path (mock PSP) | Demonstrated — deterministic decline + timeout handling, failed payments quarantined unfinalized; exact-count verify.sh assertion |
| Operational observability | Demonstrated — SLF4J logging, Prometheus counters + built-in job/listener timers, `verify.sh` cross-checks metric deltas against DB deltas |
| Throughput at 330k/day | Producer side measured — 1,015,000 due rows scanned + published in 459 s wall, peak heap 183 MiB (2026-07-21, see quality.md "Measured scale runs"); consumer-side sustained rate stays extrapolated until [R12](roadmap.md#r12) |
| Horizontal producer scaling | Demonstrated — `FOR UPDATE SKIP LOCKED` page claims + advisory-lock cron guard; exactly-once under two concurrent publishers proven by test (compose still runs a single producer instance) |

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
   or POST       │   renewal-producer   │ keyset pages/tx │
   /actuator/    │   (Spring Batch)     ├────────────────▶│  renewal_outbox
   renewal-job   │   :8080              │    publishStep  │
                 └──────────┬───────────┘  page 10000/tx  │
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
                 │ [reachable since R5] │                 │
                 └──────────┬───────────┘                 │
                            ▼                             ▼
                 ┌──────────────────────┐   invoice / charge /
                 │   renewal-consumer   ├─▶ payment / subscription
                 │   :8081 (host)       │   upserts via unique
                 └──────────┬───────────┘   constraints
                            │ POST /psp/charges
                            ▼
                 ┌──────────────────────┐
                 │ mock-psp (WireMock)  │  declines iff last hex char of
                 │ :8082 (host)         │  subscription_id ∈ PSP_FAIL_HEX
                 └──────────────────────┘
```

## The renewal job (producer)

Job `renewalJob` = `scanStep` → `publishStep`, defined in
`billing-engine/renewal-producer/.../job/RenewalJobConfig.java`.

Two ways to launch, both build the identifying parameter `scheduleDate = today`:

- **Cron** — `RenewalScheduler`, `${app.scheduleCron}` (default `0 0 3 * * *`, zone
  `${app.timezone}`). Cron launches are serialized across producer instances by a
  Postgres session advisory lock; a losing instance logs a one-line skip without
  creating a second `JobExecution`. If a peer already ran today's `scheduleDate`
  before the lock is acquired, the JobRepository rejection is caught and skipped
  cleanly. One dedicated database connection holds and explicitly releases the lock
  for the whole job; if the JVM dies, its session dies and Postgres releases the lock
  automatically, so no lease table is needed.
- **On demand** — `POST /actuator/renewal-job?force=true` (`RenewalJobEndpoint`).
  `force=true` adds a random `run.id` so the same day can be re-run; without it,
  Spring Batch rejects a repeated job instance for the same `scheduleDate`.
  The force path deliberately remains outside the scheduler guard: it is the
  operator's manual override, each force run is a distinct job instance, and
  `SKIP LOCKED` keeps concurrent runs row-safe.
  Since [R10](roadmap.md#r10) the endpoint is **asynchronous**: the POST launches
  the job on a dedicated single-thread executor via the endpoint-only
  `asyncJobLauncher` bean and returns the `executionId` immediately;
  `GET /actuator/renewal-job/{executionId}` reports live status from the
  `JobExplorer` (unknown id → 404). The cron path keeps Batch's default
  synchronous `jobLauncher` deliberately: the scheduler releases its advisory
  lock when `run()` returns, so an async launch there would release the lock
  mid-job and undo the cross-instance serialization above ([D9](decisions.md#d9)).
  The single launcher thread queues concurrent force-triggers so they run
  serially (a queued run reports `STARTING` until the thread frees).

**scanStep** — a tasklet re-run per page (`RepeatStatus.CONTINUABLE`), one
transaction per page, keyset-paginated over the primary key: each iteration
selects the next `${app.scanPageSize}` (default 10000) active subscriptions with
`s.id > cursor ORDER BY s.id LIMIT n`, applies the due-window filter
(`renewed_at` + plan interval falling in today's local-day window) *inside* the
page, and inserts the page's due rows into `renewal_outbox` with
`ON CONFLICT (subscription_id, due_date) DO NOTHING` (constraint
`uniq_outbox_sub_due`), so re-scans and crash-resumed scans stay idempotent
across pages. The page query reports its own row count and last id regardless of
how many rows were due, so all-not-due pages still advance the cursor; the loop
ends when a page comes back short, never on `inserted == 0`. The cursor — and
the due window, fixed at the first page so a scan crossing midnight keeps one
consistent window — is carried in the step ExecutionContext, which Spring Batch
persists in the same transaction as the page's inserts, so a restart resumes
from the last committed page. Each row's payload is the full
[`renewal.requested` v1 contract](#message-contract--renewalrequested-v1),
including the producer-minted identity, idempotency key, due date, and billing
period. Scanning keyset-over-all-actives instead of indexing the due predicate
is [D10](decisions.md#d10).

**publishStep** — tasklet re-run per page (`RepeatStatus.CONTINUABLE`), one page per
transaction: select `LIMIT ${app.publishPageSize}` (default 10000, raised from 1000 by [R11](roadmap.md#r11) so 1M rows publish in ~100 page transactions instead of ~1000) unpublished rows ordered
by `id` with `FOR UPDATE SKIP LOCKED`, so concurrent publishers claim disjoint pages,
then publish each via `OutboxPublisher` with correlated publisher confirms
(`spring.rabbitmq.publisher-confirm-type: correlated`) and the outbox row id as the
correlation id. The row locks intentionally span the confirm await inside the page
transaction, bounded by one `app.confirmTimeoutMs` deadline (default 10s); this keeps
in-flight rows invisible to peers until `published_at` is updated and the transaction
commits. Under READ COMMITTED, the lock-time recheck drops a row that a peer published
and committed while the statement was acquiring locks. An empty page means none are
visible to this publisher — the outbox is drained or the remainder is peer-claimed —
and the tasklet finishes rather than busy-waiting. That is safe because claims last only
for the transaction: a peer either publishes its rows or its locks end with its failed
transaction and a later page or job run re-picks them. Unconfirmed rows stay NULL and
are re-picked by the next page or job run: delivery is at-least-once, and consumer
idempotency absorbs duplicates ([G2](invariants.md#g2)). A page with zero confirmed rows
fails the job, making the failure visible instead of tight-looping over the same page.
A confirm means the exchange accepted the message; an unroutable message is confirmed
and silently dropped until publisher returns add detection in [R15](roadmap.md#r15).

Producer declares only the exchange (`RabbitConfig`); the consumer owns the rest of the topology.

## The consumer

`payment-service/renewal-consumer/.../mq/RenewalListener.java` listens on
`billing.renewals.main`, deserializes to the `RenewalRequested` record, and calls
`BillingService.process`, which runs an upsert chain — each step backed by a DB
unique constraint (this *is* the idempotency mechanism, [D2](decisions.md#d2)):

| Step | Table | Payload material / backing constraint |
|---|---|---|
| `upsertInvoice` | `invoice` | `period_start`, `period_end`; `uniq_invoice_period (customer_id, period_start, period_end, currency)` |
| `upsertCharge` | `charge` | actual `due_date`; `uniq_charge_period (subscription_id, due_date, amount_cents, currency)` |
| `upsertPayment` | `payment` | `idempotency_key`; `payment.idempotency_key UNIQUE` |
| `PspClient.charge` | — | HTTP `POST /psp/charges` to the mock PSP; called only while the payment is 'pending' |
| `markPaymentSucceeded` / `markPaymentFailed` | `payment` | terminal status + `completed_at` from the PSP outcome; 'failed' returns without finalizing |
| `finalizeBilling` | `charge`, `invoice`, `subscription` | sets settled/paid, advances `renewed_at` to `period_end` at 09:00 |

Provider declines, timeouts, 5xx responses, and unreachable-provider errors are
business failures: the payment becomes `failed`, the message is ACKed, and nothing is
sent to the DLQ, which remains reserved for unprocessable messages ([G5](invariants.md#g5)).
Failed payments are terminal and redelivery does not re-attempt them because dunning is
a non-goal; a succeeded payment is replayed through `finalizeBilling` only. A crash
between a provider 200 response and the payment-status write can call the PSP again on
redelivery. A real PSP would deduplicate the transmitted `idempotency_key`; the
stateless mock returns the same deterministic outcome.

The consumer uses validated payload values only and has no clock-derived fallbacks.
Missing or invalid required fields throw `InvalidRenewalMessageException`; deterministic
contract violations skip retry and dead-letter immediately.

**Topology** (`RabbitTopology`): the main queue has `x-dead-letter-exchange:
billing.renewals.dlx` and `x-dead-letter-routing-key: dlq`, matching the DLQ binding.
The listener makes at most five attempts with exponential backoff from 1s to a 10s cap
at a 2.0 multiplier, then `default-requeue-rejected: false` lets RabbitMQ dead-letter
the rejected message. `InvalidRenewalMessageException` is non-retryable and goes
straight to the DLQ; other failures use the bounded retry budget. Queue arguments are
immutable, so brokers carrying the pre-R5 queue must delete it or wipe the RabbitMQ
volume before redeclaration; [D4](decisions.md#d4) records why the queue name stayed.

## Mock PSP

The mock provider runs WireMock `3.13.2-alpine`. Its source mappings live as inert
templates in `mock-psp/mappings/*.json.tpl`; the Compose entrypoint renders them with
`sed`, substituting `PSP_FAIL_HEX` into the decline rule before WireMock starts. A
subscription is declined exactly when the last hex character of its UUID belongs to
that set, so `k` configured hex characters decline exactly `k/16` subscriptions; a
non-hex value such as `x` disables failures.

The consumer integration test uses the same image and templates through a
Testcontainers `GenericContainer`, then adds a test-only delayed response mapping for
the timeout path. `verify.sh` recomputes the exact expected failed set in SQL with the
same last-character predicate and asserts every due renewal has its predicted terminal
payment status.

## Observability

Both services log through SLF4J, with Logback supplied by Spring Boot's defaults.
Normal batch progress and coordination skips are INFO; an unconfirmed publish page is
WARN. A declined payment is INFO because it is an expected business outcome whose
signal is the outcome counter.

The Prometheus name is the external contract used by the roadmap and `verify.sh`.
Micrometer converts dots in meter names to underscores for Prometheus and appends
`_total` to counter names.

| Micrometer meter | Prometheus name | Type | Tags | Incremented |
|---|---|---|---|---|
| `outbox.inserted` | `outbox_inserted_total` | Counter | none | By the number of rows inserted immediately after the scan SQL update |
| `outbox.published` | `outbox_published_total` | Counter | none | By the number of confirm-gated rows immediately after their `published_at` batch update |
| `renewals.processed` | `renewals_processed_total{outcome="..."}` | Counter | `outcome=succeeded \| failed \| invalid` | Per processed delivery at its decision point: after successful finalization, at either terminal-failure return, or when validation rejects the message |

All counter series are registered eagerly and therefore render as `0.0` from boot;
`verify.sh` depends on that property. The renewal outcome taxonomy is bounded to
`succeeded`, `failed`, and `invalid`. Transient or unexpected failures increment no
outcome counter because they have no decided business outcome; retries remain visible
through the listener timer's `result="failure"` tag.

| Micrometer meter | Prometheus series | Tags |
|---|---|---|
| `spring.batch.job` | `spring_batch_job_seconds_count/_sum/_max` | `spring_batch_job_name`, `spring_batch_job_status`, `error` |
| `spring.batch.step` | `spring_batch_step_seconds_count/_sum/_max` | `spring_batch_step_name`, `spring_batch_step_job_name`, `spring_batch_step_status`, `error` |
| `spring.rabbitmq.listener` | `spring_rabbitmq_listener_seconds_count/_sum/_max` | `listener_id="renewal"`, `queue`, `result`, `exception` |

These timers come from Spring Batch observation support, auto-wired through
`@EnableBatchProcessing`'s `BatchObservabilityBeanPostProcessor`, and Spring AMQP's
`MicrometerHolder`; they are deliberately not hand-rolled. The `error` tag on the
batch series is added by Spring Boot's observation handler, not by Batch itself;
tag sets above match the live `/actuator/prometheus` output. The end-to-end verifier
cross-checks same-run metric deltas against database deltas because counters reset with
the service process while the database persists.

## Message contract — renewal.requested v1

The producer writes all contract fields into the outbox payload in the same scan
transaction that creates the renewal intent ([D8](decisions.md#d8)).

| Field | JSON type | Source (producer SQL) | Semantics |
|---|---|---|---|
| `schema_version` | number | literal `1` | Contract version. |
| `event_id` | string | one `gen_random_uuid()` value generated in the event CTE | Event identity; exactly equal to the containing outbox row `id`. |
| `subscription_id` | string | `subscription.id` | Subscription being renewed. |
| `customer_id` | string | `subscription.customer_id` | Customer billed by the renewal. |
| `plan_id` | string | `subscription.plan_id` | Plan snapshot identity at scan time. |
| `interval` | string | `plan.interval` | Plan interval snapshot (`month` or `year`). |
| `amount_cents` | number | `plan.price_cents` | Amount to bill in integer minor units. |
| `currency` | string | `plan.currency` | Currency code paired with `amount_cents`. |
| `idempotency_key` | string | subscription id plus formatted due date | Stable payment key: `sub-<subscription_id>\|<due_date>`. |
| `due_date` | string | local `due_ts`, cast to date and ISO-formatted | Actual charge due date (`YYYY-MM-DD`). |
| `period_start` | string | `due_date` | Billed period start; equal to `due_date`. |
| `period_end` | string | `due_date` plus `plan.interval` | Billed period end: one month or one year after `due_date`. |
| `occurred_at` | string | scan transaction `now()`, formatted in UTC | ISO-8601 event creation timestamp with zone information. |

Per [G8](invariants.md#g8), changes within v1 are additive only. Consumers tolerate
unknown fields explicitly through `@JsonIgnoreProperties(ignoreUnknown = true)` (and
Spring Boot's default ObjectMapper behavior). Removing or re-typing a field requires a
version bump and a decision entry; see [D8](decisions.md#d8).

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

Every remaining `application.yaml` key has a real consumer.

| Key (application.yaml) | Consumed by | Status |
|---|---|---|
| `spring.application.name` (both) | Spring Boot application identity | alive |
| `spring.datasource.*` | Spring Boot autoconfig (overridden by compose `SPRING_DATASOURCE_*`) | alive (placeholder values in yaml) |
| `spring.jackson.time-zone` (both) | Spring Boot Jackson autoconfig | alive |
| `spring.batch.jdbc.initialize-schema` (producer) | Spring Batch | alive |
| `spring.rabbitmq.publisher-confirm-type` (producer) | Spring Boot AMQP autoconfig (`CachingConnectionFactory` confirm type); load-bearing: without it confirm futures never complete and every page times out | alive |
| `app.timezone`, `app.scheduleCron`, `app.scanPageSize`, `app.publishPageSize`, `app.confirmTimeoutMs` (producer) | `RenewalScheduler`, `RenewalJobConfig`, `RenewalJobEndpoint` | alive |
| `rabbitmq.exchange`, `rabbitmq.routingKey` (producer) | `RabbitConfig`, `OutboxPublisher` | alive |
| `rabbitmq.exchange/queue/routingKey` (consumer) | `RabbitTopology`, `RenewalListener` | alive |
| `payment.provider.base-url` (consumer) | `PaymentProviderProperties`, `PspClient`; compose overrides with `PAYMENT_PROVIDER_BASE_URL` | alive |
| `payment.provider.timeout-ms` (consumer) | `PaymentProviderProperties`, `PspClient` connect + read timeout | alive |
| `spring.rabbitmq.listener.simple.*` (consumer) | Spring Boot AMQP autoconfig + `ListenerRetryConfig` (`max-attempts`) | alive |
| `management.endpoints.web.exposure.include` (producer) | actuator exposure for `health`, `info`, `metrics`, `prometheus`, and `renewal-job` | alive |
| `management.endpoints.web.exposure.include` (consumer) | actuator exposure for `health`, `info`, `metrics`, and `prometheus`; the compose healthcheck relies on `health` | alive |
| `management.endpoint.health.show-details` (producer) | actuator health response detail policy | alive |

[R2](roadmap.md#r2) replaced the producer's dotted app-specific environment names with
Spring relaxed-binding underscore names and added named-volume defaults for Postgres
and RabbitMQ; local `.env` values can still select bind-mount paths. The consumer's
compose healthcheck hits `/actuator/health`, served by actuator since
[R1](roadmap.md#r1).

## Ports & endpoints

| Where | What |
|---|---|
| `localhost:8080` | producer — `/actuator/health`, `/actuator/prometheus`, `POST /actuator/renewal-job?force=true`, `GET /actuator/renewal-job/{executionId}` |
| `localhost:8081` | consumer — `/actuator/health` (since [R1](roadmap.md#r1)), `/actuator/prometheus`; container-internal 8080 |
| `localhost:8082` | mock PSP (WireMock) — POST `/psp/charges`; admin/journal at `/__admin` |
| `localhost:5672` / `15672` | RabbitMQ AMQP / management UI (creds from `.env`) |
| `localhost:5432` | Postgres (creds from `.env`) |
