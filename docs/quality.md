# Quality grades

Coarse, honest, and **maintained**: any PR touching a module re-grades it in the same
PR ([G6](invariants.md#g6)). The value of this file is currency, not precision.

Last full re-grade: **2026-07-31** (R13 entropy pass, after the R23 epic, R27, and R29).

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
| `billing-engine/renewal-producer` | **A** | Tested (smoke, confirm-gating, return-gating, unroutable-return, competing-publisher, async-trigger, keyset-scan, channel-parking, and in-flight-window suites, on Testcontainers 2.x with no machine-local Docker pins), observable (eager counters + built-in batch timers), documented; scan and publish both page in bounded memory and the 1M-row producer run is measured (see “Measured scale runs”); unroutable messages are returned, logged, counted, and re-picked instead of silently confirm-dropped; the publish page holds a bounded channel budget under a slow-confirming broker ([R25](roadmap.md#r25)) instead of piling channels to the broker's channelMax; no known behavior defects | — |
| `payment-service/renewal-consumer` | **A** | Tested (real-broker integration suite including synchronous card decline, authorization timeout to bounded DLQ, asynchronous card settlement/chargeback, poison isolation, deterministic BE→bank-a/NL→bank-b routing, per-counterparty webhook secrets, and the shared settlement spine, with DB polls that treat a missing row as retriable within their bounded windows, [R29](roadmap.md#r29)), observable (method-tagged eager renewal counters, webhook/settlement counters, per-counterparty latency timers, Prometheus, listener timers, dashboard panels), and documented; both methods now use one registry/client and one durable inbox→queue→listener path, while the removed PSP client can no longer confuse transport failure with a decline; no known behavior defects | — |
| `db-migrations` | **B** | Clean, ordered, sole schema authority; since R18 also ships as the `payfold-migrations` Job image (SQL baked at build, FLYWAY_* env config), whose no-op re-run `verify.sh` asserts; V6 adds customer payment methods and submitted-payment attribution, V7–V8 add the settlement lifecycle, and V9 adds/backfills the required tokenized card reference; V1 carries aspirational tables (`bank_tx`, `recon_match`, `ledger_entry`) no code uses — harmless but reviewer-confusing | — |
| `seed-data-gen` | **B** | Seed size parameterized (`SEED_CUSTOMERS`, default 15k, all due today); payment-method, rule-bearing IBAN, and rule-bearing card-token shares use deterministic customer-number arithmetic with no RNG; card suffixes cycle exact auth/chargeback cases, while SDD rows keep card tokens null; emails remain collision-safe across top-ups; the R18 Job image and R16 clamp-day-safe due seeding remain unchanged. No test harness of its own — the arithmetic is checked by end-to-end exact outcome assertions | — |
| `mock-bank/` (FastAPI) | **B** | One counterparty image now implements `sepa_core` and `card`: deterministic IBAN settlement rules plus token-derived synchronous authorization, async card settlement/chargeback, HMAC-signed delivery, bounded exponential retry, duplicate stored-verdict semantics, and loud give-up metrics; pytest covers both schemes including default/decline/chargeback rules, no-notification declines, ordered callbacks, duplicates, and model isolation; compose healthchecks all three instances; pending deliveries are in-memory asyncio tasks, so a container recreate silently drops not-yet-fired notifications | [R28](roadmap.md#r28) |
| `docker-compose.yaml` + config | **B** | Stack ordering and healthchecks pass; yaml contains only consumed keys; flyway and seed-data run the published image shapes; the WireMock PSP service and `payment.provider.*` surface are removed, while the same counterparty image runs bank-a, bank-b, and cardnet on port 8087 with a scheme-aware registry; Prometheus/Grafana, scale-safe consumer ports, and confirm-gated settlement relay configuration remain intact | — |
| `docs/` + harness | **B** | CI uses pinned Maven wrappers and real-container integration suites; `verify.sh` covers trigger/idempotency/poison behavior, exact token- and IBAN-predicted outcomes and reasons, method-tagged renewal counters, all-payment zero-stuck and inbox reconciliation, per-counterparty exact inbox shares, health, and zero give-ups; `scripts/chaos-demo.sh` keeps seven asserted scenes and now seeds clean cards for delivery demonstrations while cards settle asynchronously on the same spine | — |

## Test coverage

Both services have JUnit 5 integration coverage backed by Testcontainers 2.x and the
real V1–V9 migrations. The producer has a context smoke test and a confirm-gating job test
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
`renewal.requested` messages through RabbitMQ and uses WireMock only as a generic
counterparty HTTP stub. It covers cross-midnight redelivery, token-derived card
declines, authorization transport failure exhausting exactly five attempts into
the DLQ without inventing a verdict, authorized cards parking submitted, and
settlement-driven card success. The same stub exercises SDD routing: accepted
collections park submitted without finalization, BE and NL customers hit only
bank-a and bank-b, duplicate delivery skips resubmission, and bank-a 5xx responses
leave the payment pending after bounded DLQ delivery. The settlement spine suite covers per-bank signed receiver semantics
(including bank-b rejecting bank-a's secret), durable webhook deduplication,
confirm-relayed inbox rows, idempotent settlement redelivery, per-bank latency
recording, terminal failure reasons, chargebacks after settlement and before
settlement, idempotent chargeback redelivery, card settlement followed by a
`fraud_dispute` chargeback, and poison settlements reaching their
dedicated DLQ while good messages flow. The suite also
covers the renewal poison path: malformed and contract-violating messages dead-letter
while a subsequent good message processes.
CI runs both suites with each module's pinned Maven wrapper.

The producer confirm-gating test asserts inserted and confirmed-published counter
deltas, while the consumer happy-path test asynchronously awaits the succeeded outcome
counter delta. `verify.sh` also polls Prometheus and cross-checks same-run counter
deltas against outbox database deltas.

`scripts/verify.sh` remains the end-to-end black-box check for the full Compose stack,
including the async trigger-then-poll happy path, same-day idempotency, a strict poison-message DLQ probe, and
exact deterministic card-token and SDD-IBAN outcome assertions.

## Measured scale runs

- **2026-07-26 — consumer scaling levers (R20, the commit this entry ships in;
  WSL2 Docker Compose stack).** Three fresh-boot 100k-due-today runs, each
  passing the full verify.sh; drain windows measured from `payment` timestamps
  (`max(completed_at) − min(requested_at)`), rates cross-checked by 60 s
  Prometheus sampling. *Baseline* (1 replica × concurrency 1): steady
  **52–54/s** across twenty 60 s intervals — consistent with R12's 48–55/s.
  *Concurrency 8* (one JVM, `CONSUMER_LISTENER_CONCURRENCY=8`): 100,000 in
  **191 s = 524/s average, ~10× baseline** — mildly superlinear per thread
  because prefetched deliveries pipeline instead of paying the full
  per-message round-trip chain. *3 replicas × concurrency 1*
  (`--scale renewal-consumer=3`): 100,000 in **951 s = 105/s, ~2× baseline**;
  RabbitMQ round-robin split the work **33,335 / 33,320 / 33,345** (0.08%
  spread) — the load-balancing evidence — but three same-host JVMs contend for
  one machine's CPU, so in-process concurrency is the cheaper local lever and
  replica scaling pays off across real nodes (the platform repo's KEDA
  autoscaling). First multi-replica verify.sh green: the fleet-summed consumer
  checks matched DB deltas exactly at 3 replicas.

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
