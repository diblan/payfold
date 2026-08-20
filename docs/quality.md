# Quality grades

Coarse, honest, and **maintained**: any PR touching a module re-grades it in the same
PR ([G6](invariants.md#g6)). The value of this file is currency, not precision.

Last full re-grade: **2026-08-08** (R13 entropy pass, after the dunning epic, R30–R32, R34, and R35).

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
| `billing-engine/renewal-producer` | **A** | Tested (smoke, confirm-gating, return-gating, unroutable-return, competing-publisher, async-trigger, keyset-scan — its payload asserts now follow the clamp-day seed interval, [R31](roadmap.md#r31) — channel-parking, and in-flight-window suites, on Testcontainers 2.x with no machine-local Docker pins), observable (eager counters + built-in batch timers), documented; scan and publish both page in bounded memory and the 1M-row producer run is measured (see “Measured scale runs”); unroutable messages are returned, logged, counted, and re-picked instead of silently confirm-dropped; the publish page holds a bounded channel budget under a slow-confirming broker ([R25](roadmap.md#r25)) instead of piling channels to the broker's channelMax; no known behavior defects | — |
| `payment-service/renewal-consumer` | **A** | Tested (real-broker integration plus direct-service grace coverage for retriable, hard-fail, dispute, reordered chargeback, settled no-op, and redelivery idempotency; recovery sweeper inbox synthesis, 404 resubmission, freshness, scheduled-state noop, and race dedupe; dunning re-collection through the submission spine, duplicate-tick suppression, hard-fail/dispute and submitted-row exclusion, card re-decline, and settled recovery; exhaustion and grace-expiry cancellation with terminal no-ops under late settlements; bounded DLQs and routing), observable (eager renewal, webhook, settlement, recovery-sweep, retry-outcome, dunning-recovery, and class-tagged dunning counters; past-due gauge; latency/listener timers — the headline series carried as pipeline-dashboard panels), and documented; both methods share one complete grace lifecycle while preserving period math — a settled retry returns `past_due` to `active` with stale grace cleared, and bounded exhaustion or grace expiry ends it terminally `canceled` (R26c/D21); overload at a counterparty is backpressure (blocked listener threads under a 10 s budget), never poison ([D19](decisions.md#d19)); both sweepers defer their first scheduled tick one full interval past startup so a long test interval structurally silences the schedule (R35); the consume chain's invoice/charge/payment upserts commit as one transaction (D22) and the R36 wall-clock regression is closed — per-consume is ~6 ms since the counterparty stall fix (D23); no known behavior defects | — |
| `db-migrations` | **B** | Clean, ordered, sole schema authority; since R18 also ships as the `payfold-migrations` Job image (SQL baked at build, FLYWAY_* env config), whose no-op re-run `verify.sh` asserts; V6 adds customer payment methods and submitted-payment attribution, V7–V8 add settlement, V9 adds/backfills tokenized card references, V10 adds the dunning grace deadline plus a partial past-due index, and V11 adds constraint-keyed payment attempts for race-safe re-collection; V1 carries aspirational tables no code touches (`bank_tx`, `recon_match`, `ledger_entry`, plus the unused `payment_method` and `mandate` tables — the live payment-method and mandate-reference data are V6 *columns* on `customer`) — harmless but reviewer-confusing | — |
| `seed-data-gen` | **B** | Seed size parameterized (`SEED_CUSTOMERS`, default 15k, all due today); payment-method, rule-bearing IBAN/card-token shares, suffix-94 silent shares, and additive suffix-95 recoverable shares use deterministic customer-number arithmetic with no RNG; card suffixes cycle exact auth/chargeback cases, while SDD rows keep card tokens null; emails remain collision-safe across top-ups; the R18 Job image and R16 clamp-day-safe due seeding remain unchanged. No test harness of its own — the arithmetic is checked by end-to-end exact outcome, recovery, and re-collection assertions | — |
| `mock-bank/` (FastAPI) | **B** | One counterparty image implements `sepa_core` and `card`: deterministic IBAN settlement rules plus token-derived synchronous authorization, attempt-indexed suffix-95 fail-then-recover verdicts derived only from collection identity, async card settlement/chargeback, HMAC-signed delivery, bounded exponential retry, duplicate stored-verdict semantics, and loud give-up metrics; pytest covers both schemes including default/decline/chargeback/silent/recoverable rules, no-notification declines, suppressed delivery, ordered callbacks, duplicates, and model isolation; compose healthchecks all three instances. In-memory pending-delivery loss is modeled by the silent rule and recovered by the consumer sweeper's re-query/resubmit path. Scales past one event loop via `BANK_WORKERS` uvicorn processes with worker-aggregated `/metrics` — worker-safe because records are a cache over deterministic outcomes, not truth ([D19](decisions.md#d19)); suffix-95 likewise survives worker hops and restart amnesia ([D20](decisions.md#d20)); the measured conc-8 100k run keeps it off the critical path; every accepted socket enforces TCP_NODELAY at import time (uvicorn's multi-worker path never sets it — the diagnosed ~40 ms Nagle stall behind R36, fixed by D23 and guarded by a pytest) | — |
| `docker-compose.yaml` + config | **B** | Stack ordering and healthchecks pass; yaml contains only consumed keys; flyway and seed-data run the published image shapes; bank-a, bank-b, and cardnet share the scheme-aware image; deterministic seed shares plus verify/demo-fast recovery and per-class dunning grace overrides are explicit; Prometheus/Grafana expose recovery and dunning alongside the scale-safe consumer ports and confirm-gated settlement relay; the past_due stat reads the non-additive gauge with `max()` (spot-checked true at 1 and 3 replicas) and outcome colors match only label values that exist, `charged_back` and `submitted` included (R37) | — |
| `docs/` + harness | **B** | CI uses pinned Maven wrappers and real-container integration suites; `verify.sh` covers trigger/idempotency/poison behavior, exact token- and IBAN-predicted outcomes and reasons, method-tagged renewal counters, zero-stuck and inbox reconciliation, suffix-94 recovery provenance/counter exactness, and due-cohort exact `past_due` deadlines plus fleet-summed per-class transition deltas; `scripts/chaos-demo.sh` keeps nine asserted scenes, including live counterparty-amnesia recovery without manual intervention and the dunning arc — a 95 cohort visibly failing into `past_due` and recovering, a 99 cohort exhausting into cancellation with its attempt count and cause counter exact and no re-collection across a sweep interval; every provisioned dashboard panel query is executed against live Prometheus at the end of the run and must return data — a deliberately broken panel demonstrably fails (R33) | — |

## Test coverage

Both services have JUnit 5 integration coverage backed by Testcontainers 2.x and the
real V1–V11 migrations. The producer has a context smoke test and a confirm-gating job test
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
The isolated dunning grace suite invokes `SettlementService` and `BillingService`
directly to prove each reason class, both chargeback arrival orders, card-decline
redelivery, deadline preservation, period-advance preservation, and the settled no-op.
CI runs all suites with each module's pinned Maven wrapper.

The producer confirm-gating test asserts inserted and confirmed-published counter
deltas, while the consumer happy-path test asynchronously awaits the succeeded outcome
counter delta. `verify.sh` also polls Prometheus and cross-checks same-run counter
deltas against outbox database deltas.

`scripts/verify.sh` remains the end-to-end black-box check for the full Compose stack,
including the async trigger-then-poll happy path, same-day idempotency, a strict poison-message DLQ probe, and
exact deterministic card-token and SDD-IBAN outcome assertions.

## Measured scale runs

- **2026-08-20 — the [R39](roadmap.md#r39) conc-1 100k re-measure attempt:
  red, not a measurement — the settlement relay is the post-D23 constraint
  (the commit this entry ships in; WSL2 Docker Compose stack, k3d neighbor
  cluster active at ~0.1–0.7 of one core on 20).** Fresh-boot 100k at 1
  replica × concurrency 1: drain 100,000 in **1,236 s = 81/s** (Prometheus
  cross-check: 193/s first-minute peak, sagging as the settlement spine
  loads the same JVM and WAL — the 15k demo scale still measures 195/s),
  end-to-end completion **1,685 s** — but `verify.sh --no-up --timeout 3600`
  went **red** on three checks, all downstream of one mechanism.
  [SettlementInboxRelay](../payment-service/renewal-consumer/src/main/java/com/blanchaert/billing/consumer/mq/SettlementInboxRelay.java)
  publishes each inbox row with a serialized per-row confirm wait plus a
  per-row UPDATE (100-row pages, 500 ms cadence): measured ~135/s peak /
  ~59/s mean against webhook arrival that tracks the consume rate (p50
  receive lag 1.0 s), so the backlog accumulates as *unpublished inbox rows*
  (relay lag p50 166 s / max 322 s; the settlements queue never exceeded
  1,014). The wall-clock dunning grace then races that backlog: 2,019 of
  4,000 suffix-95 subscriptions grace-expiry-canceled despite every one
  settling on attempt 2 (canceled cohort's attempt-1→settled-attempt-2 gap:
  min 187 s ≈ the 180 s retriable grace, median 341 s), and 4 chargebacks
  arrived reordered via delivery retries, stranding invoices `posted` and
  subscriptions unadvanced-then-canceled. Filed as [R40](roadmap.md#r40)
  (relay throughput / grace race) and [R41](roadmap.md#r41) (reordered
  chargeback semantics); the ×3-replica and conc-8 configs were not run —
  the matrix re-measures after both land. The 2026-08-01 numbers below stand
  as the current dated record.

- **2026-08-08 — the consume-cost regression diagnosed to a socket option;
  conc-1 more than tripled (R36/D22/D23, the commit this entry ships in; WSL2
  Docker Compose stack).** [R36](roadmap.md#r36)'s 16 → ~43 ms consume-cost
  regression decomposed by measurement: `pg_stat_activity` sampling first
  implicated WAL flush ([D22](decisions.md#d22) batched the
  invoice/charge/payment upserts into one transaction — kept: DB-active
  occupancy fell to ~6% ≈ 3 ms/consume), but a JFR recording of the renewal
  listener found the real mechanism — exactly one 40–50 ms socket read per
  message on the counterparty HTTP hop. Multi-worker uvicorn (cardnet since
  [D19](decisions.md#d19)) never sets TCP_NODELAY on accepted sockets, so
  Nagle held each response body against the peer's ~40 ms delayed ACK on the
  container bridge — invisible through host docker-proxy, which is how every
  earlier benchmark missed it (isolated A/B, container-to-container: 44.1 ms
  p50 at 4 workers vs 0.83 ms at 1; 0.85 ms at 4 workers with the
  [D23](decisions.md#d23) NODELAY enforcement). Post-fix fresh-boot 15k at
  conc-1: full verify.sh green (113 checks, `--timeout` re-tightened to 600),
  drain 15,000 in **77 s = 195/s** (listener mean 5.4–6.1 ms vs ~16 ms at R30
  and ~41 ms during the regression); **end-to-end completion 163 s** including
  both silent-cohort recoveries and the complete dunning arc
  (exhausted=350 / grace_expired=850 cause counters exact at the D23-retuned
  180 s retriable grace). The 100k lever matrix predates the fix and is
  re-measured under [R39](roadmap.md#r39).

- **2026-08-01 — concurrency-8 after the counterparty capacity fix (R32, the
  commit this entry ships in; WSL2 Docker Compose stack).** The [R30](roadmap.md#r30)
  collapse run re-executed on the [D19](decisions.md#d19) design (cardnet at 4
  uvicorn workers, submit timeout 2 s → 10 s, sweeper scheduled-state noop):
  fresh-boot 100k, `CONSUMER_LISTENER_CONCURRENCY=8`, full
  `verify.sh --no-up --timeout 3600` green (151 checks) with **zero
  dead-lettered renewals and zero submit timeouts** — average submit round
  trip ≈ 53 ms (8 threads at ~150/s), so the counterparty is off the critical
  path. Drain 100,000 in **638 s = 157/s** (~190/s two-minute warm-up,
  ~145–155/s sustained, no oscillation); end-to-end completion **1,739 s** —
  the ~18-minute settlement tail is the single JVM's inbox relay + settlement
  listener working through the backlog the drain built (the 3-replica fleet
  runs three relays; this run runs one). Both silent cohorts (2,000 SDD +
  2,000 card) recovered exactly through the sweeper across the 4-worker
  record partition. Honest reading: conc-8 drain now ~equals the 3×1 fleet's
  156/s — on one host the ceiling has moved from the counterparty mock to the
  shared substrate (one Postgres absorbing every write, one JVM doing
  submit + webhook + relay + settle), which is where a single-machine demo
  should cap.

- **2026-08-01 — async-spine re-measurement (R30, the commit this entry ships
  in; WSL2 Docker Compose stack).** Since [R23f](roadmap.md#r23f) a consume is
  a fast synchronous auth/submission while settlement arrives asynchronously,
  so the sync-card era's one probe is now two quantities: renewals-queue
  drain (every message consumed) and end-to-end completion (every payment
  terminal, zero stuck `submitted`). Three fresh-boot 100k-due-today runs on
  the current system — [R28](roadmap.md#r28)'s recovery sweeper and
  [R26a](roadmap.md#r26a)'s grace lifecycle included, so each run also
  recovers its 2,000-payment silent cohort and moves 12,000 subscriptions to
  `past_due` — windows from `payment` timestamps on the Postgres clock (WSL2
  JVM-timer skew avoided), rates cross-checked by 60 s Prometheus sampling.
  *Baseline* (1 replica × concurrency 1): full `verify.sh --no-up` green (151
  checks); drain 100,000 in **1,606 s = 62.3/s** (~94/s two-minute warm-up,
  ~58/s sustained); end-to-end completion **1,639 s** — the settlement tail
  past the last consume is ~33 s (max bank delay + chargeback lag + one
  recovery-sweep cycle). Faster than the sync era's ~48/s because the handler
  no longer settles in-line.
  *3 replicas × concurrency 1* (`--scale renewal-consumer=3`): full verify.sh
  green; drain 100,000 in **642 s = 156/s, ~2.5× baseline** (burst
  ~200–360/s, sustained ~110–120/s); end-to-end **675 s** with the same ~33 s
  tail; RabbitMQ round-robin split **33,338 / 33,307 / 33,355** (0.14%
  spread).
  *Concurrency 8* (one JVM, `CONSUMER_LISTENER_CONCURRENCY=8`): **did not
  complete — measured collapse.** The consume burst saturates the
  single-worker mock counterparties (cardnet absorbs ~80% of submissions plus
  one signed webhook delivery per settlement on a single event loop); 2 s
  submit timeouts ride the bounded listener retry, drain oscillates 0–26/s
  after a 64/s first minute, and 654 good renewals dead-letter (19,797 of
  100,000 consumed in 44 min before the run was stopped). Filed as
  [R32](roadmap.md#r32) with the mechanism. The retired pre-R23f 524/s figure
  was measured against WireMock, which did no outbound work; the counterparty
  mock — not the consumer — is the async era's high-concurrency ceiling, and
  the 3-replica fleet's ~190/s warm-up aggregate clears it cleanly.

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
