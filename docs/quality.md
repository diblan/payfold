# Quality grades

Coarse, honest, and **maintained**: any PR touching a module re-grades it in the same
PR ([G6](invariants.md#g6)). The value of this file is currency, not precision.

Last full re-grade: **2026-07-21** (R13 entropy pass).

## Rubric

| Grade | Meaning |
|---|---|
| A | Tested, observable, documented; no known defects |
| B | Solid; known gaps are cosmetic or tracked with low risk |
| C | Happy path works; known correctness or scale gaps tracked on the roadmap |
| D | Contains defects that mislead (broken healthcheck, unreachable safety net, dead config) |

## Grades

| Module | Grade | Why | Tracked by |
|---|---|---|---|
| `billing-engine/renewal-producer` | **A** | Tested (smoke, confirm-gating, return-gating, unroutable-return, competing-publisher, async-trigger, and keyset-scan suites, on Testcontainers 2.x with no machine-local Docker pins), observable (eager counters + built-in batch timers), documented; scan and publish both page in bounded memory and the 1M-row producer run is measured (see “Measured scale runs”); unroutable messages are returned, logged, counted, and re-picked instead of silently confirm-dropped; no known behavior defects | — |
| `payment-service/renewal-consumer` | **A** | Tested (real-broker integration suite including decline, timeout, and poison paths, on Testcontainers 2.x with no machine-local Docker pins), observable (SLF4J, `renewals_processed_total{outcome}`, Prometheus endpoint, and listener timer), and documented (contract + architecture); no known behavior defects | — |
| `db-migrations` | **B** | Clean, ordered, sole schema authority; V1 carries aspirational tables (`bank_tx`, `recon_match`, `ledger_entry`) no code uses — harmless but reviewer-confusing | — |
| `seed-data-gen` | **B** | Seed size parameterized (`SEED_CUSTOMERS`, default 15k, all due today); emails numbered from the current row count so `customer_email_key` cannot collide at any size; dead `SubscriptionSeeder.java` deleted; the documented 100k run seeds in ~5 s and passed verify.sh. Remaining gaps: `run-seeder.bat` drift (cosmetic) and month-end clamp days | [R16](roadmap.md#r16) |
| `mock-psp/` (WireMock) | **B** | Deterministic decline rule (last-hex-char class) rendered from inert `.json.tpl` templates by the compose entrypoint; healthchecked; exercised end-to-end by the consumer integration suite and `verify.sh`'s exact per-row assertions. The sed-render entrypoint itself has no direct test | — |
| `docker-compose.yaml` + config | **B** | Stack ordering and healthchecks pass; app-specific env names use relaxed binding, yaml contains only consumed keys, and declared named-volume defaults preserve path overrides | — |
| `docs/` + harness | **B** | CI uses pinned Maven wrappers and runs real-container integration tests for both services; `verify.sh` covers the happy path via the async trigger (<1s POST assert + execution-status polling), same-day idempotency, poison probe, per-row predicted PSP outcomes with an exact failed count, and same-run metric/DB delta cross-checks | — |

## Test coverage

Both services have JUnit 5 integration coverage backed by Testcontainers 2.x and the
real V1–V4 migrations. The producer has a context smoke test and a confirm-gating job test
against a real-PostgreSQL container with publisher futures faked; the latter proves
unconfirmed rows stay unpublished and are re-picked.
`PublisherReturnGatingTest` extends that recipe one level deeper — the
`RabbitTemplate` itself is mocked, and a message that is acked but also returned
resolves unconfirmed, so its row stays unpublished and is re-picked.
`UnroutableReturnIntegrationTest` proves the same end to end: a real RabbitMQ broker
with the exchange declared but no queue bound returns every publish, every row keeps
`published_at` NULL, and the job fails on the zero-progress page.
`CompetingPublishersTest` proves
two simultaneous publishers claim disjoint pages, publish each row exactly once, and
leave none skipped.
`AsyncTriggerEndpointTest` proves the endpoint trigger returns an execution id
while the job is still gated mid-publish, and that the endpoint's read operation
tracks the execution to COMPLETED.
`ScanKeysetPaginationTest` drives the whole job through three keyset pages (page size
2), proves a never-renewed subscription stays invisible to the scan, field-checks a
sample payload against the v1 contract, and re-runs the job to prove cross-page
re-scan dedup (zero new rows, zero inserted-counter delta, no re-publish).
The consumer suite publishes real
`renewal.requested` messages through RabbitMQ, covers cross-midnight redelivery
idempotency, and covers provider decline, timeout, and no-retry-on-redelivery through
the real broker and a WireMock container. It also covers the poison path: malformed
and contract-violating messages dead-letter while a subsequent good message processes.
CI runs both suites with each module's pinned Maven wrapper.

The producer confirm-gating test asserts inserted and confirmed-published counter
deltas, while the consumer happy-path test asynchronously awaits the succeeded outcome
counter delta. `verify.sh` also polls Prometheus and cross-checks same-run counter
deltas against outbox database deltas.

`scripts/verify.sh` remains the end-to-end black-box check for the full Compose stack,
including the async trigger-then-poll happy path, same-day idempotency, a strict poison-message DLQ probe, and
exact deterministic provider-failure assertions.

## Measured scale runs

- **2026-07-21 — 1M producer run (R11, the commit this section ships in; WSL2 Docker
  Compose stack, no config overrides).** Seeded 1,015,000 active due-today
  subscriptions (ad-hoc `generate_series` SQL on top of the 15k demo seed; tooling
  deliberately uncommitted — [R12](roadmap.md#r12) owns the durable load script).
  `renewalJob`, triggered through the async endpoint, **COMPLETED in 459 s wall**
  (execution-status `endTime − startTime`, 10:49:23 → 10:57:02, corroborated by
  external polling). **Peak producer heap 183 MiB** against a 3.9 GiB max
  (page-shaped sawtooth; container RSS 654 MiB) — keyset scan pages (10k) and
  publish pages (10k) hold memory flat at 68× demo scale.
  `outbox_inserted_total` and `outbox_published_total` both ended at exactly
  1,015,000 with zero unpublished rows: every page fully confirmed, no
  zero-progress failures, `app.confirmTimeoutMs` untouched. RabbitMQ absorbed the
  ~1M-deep queue at ~260 MiB broker memory (no flow control). Step split per the
  batch timers: scan ≈ 23 s, publish ≈ 482 s — but note those monotonic-clock
  timers summed to 505 s, ~10% above wall clock (WSL2 nanoTime drift); wall clock
  is the recorded truth. The consumer was still draining (~41 msg/s sustained,
  failed:succeeded ratio matching the 1/16 PSP rule) when the stack was reset —
  consumer-side sustained rate is [R12](roadmap.md#r12)'s measurement.
  15k parity after the reshape: scanStep 0.356 s / publishStep 2.775 s versus the
  0.327 s / 3.008 s single-transaction baseline (commit a22cab3) — no regression
  at demo scale.

- **2026-07-21 — 100k end-to-end run (R12, the commit this entry ships in; WSL2
  Docker Compose stack, no config overrides).** Fresh reset, then
  `SEED_CUSTOMERS=100000 docker compose up -d --build`: the seed container compiled
  and inserted 100,000 customers + 100,000 due-today subscriptions in **5.2 s**.
  `scripts/verify.sh --no-up --timeout 3600` **passed every check**: `renewalJob`
  COMPLETED in **21.6 s wall** (~4.6k msg/s publish; execution timestamps), and the
  consumer drained all 100,000 renewals in **~30 min — ~55/s overall, ~48/s
  sustained** after a ~2-minute ~150/s warm-up burst (`renewals_processed_total`
  sampled every 60 s against wall clock), with the failed count exactly
  6225/100000 per the PSP_FAIL_HEX=0 rule. A first, otherwise-identical run the
  same day measured a 53/s steady drain and failed only "main queue empty after
  poison message" on a stale management-API read (the live queue was empty) —
  filed as [R17](roadmap.md#r17); the re-run passed clean.
  `scripts/load-test.sh 50000`, exercised against the running 15k stack: seeded
  50k extra due-today subscriptions in 1 s, job COMPLETED in 9 s (5,556 msg/s),
  peak producer heap 77 MiB. The consumer is the binding constraint; untested
  levers are listener concurrency and additional consumer instances (safe under
  [G2](invariants.md#g2)'s constraint-based idempotency). Extrapolation math lives
  in README "Scale: measured, not claimed".
