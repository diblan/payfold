# Roadmap

The only sanctioned way work enters this repo. **Each session executes exactly one
unchecked item** — the top-most unblocked one unless the user says otherwise. Defects
noticed en route become *new items here*, not drive-by fixes. An item is done when its
acceptance criteria hold, `scripts/verify.sh` passes, and [quality.md](quality.md) is
re-graded for touched modules — all in the same PR.

Sessions that touch only `docs/`, `AGENTS.md`, or `README.md` (no behavior change) are
exempt and don't consume an item.

## Non-goals

Scope insurance. Promoting any of these onto the roadmap requires a
[decisions.md](decisions.md) entry first — that friction is intentional.

| Non-goal | Why not |
|---|---|
| Kubernetes / cloud deploy | Compose demonstrates the architecture; orchestration adds ops surface, not distributed-systems insight. [D11](decisions.md#d11) sanctions one epilogue — publishing versioned images for the external platform repo ([R18](#r18)); orchestration itself stays out |
| Real PSP or money movement | Mock counterparties only: the mock PSP ([R8](#r8)) pioneered the deterministic failure path; the mock bank joins it in [R23](#r23), and the async card scheme absorbs WireMock's role in [R23f](#r23f) ([D15](decisions.md#d15)/[D17](decisions.md#d17)) — still no credentials, compliance, or real money |
| Auth / multi-tenancy | Orthogonal to the billing pipeline story |
| Any UI | The consumers of this system are curl, psql, and the RabbitMQ console. [D12](decisions.md#d12) carves out provisioned Grafana ([R21](#r21)) — industry ops tooling as code, not a custom page |
| Proration, refunds, tax | Each is a project of its own; the renewal happy path + failure path is the thesis. Dunning left this row 2026-07-26: [D16](decisions.md#d16) promoted it to [R26](#r26), shipped 2026-08-01 as R26a–R26d |
| Event-sourcing rewrite | The outbox pattern *is* the demonstration; rewriting the persistence model restarts the project |
| More services | Two services already demonstrate cross-service delivery semantics; a third must earn its place via a decision entry — [D13](decisions.md#d13) grants exactly one: the mock-bank settlement service ([R23](#r23)) |
| Reconciliation / ledger flows | `bank_tx`, `recon_match`, `ledger_entry` stay dormant until promoted |

## Items

Ordering principle: *repair the feedback loop → correctness → resilience → scale → story*.
Consumed dependency/phase notes are pruned by entropy passes; the one live edge:
[R24](#r24) (recording) waits on nothing but the user — see its re-record note.

<a id="r1"></a>
### [x] R1 — Consumer bootstrap hygiene
**Scope:** `payment-service/renewal-consumer/pom.xml` only.
Add `spring-boot-starter-actuator`; replace `spring-boot-starter-webflux` with
`spring-boot-starter-web`; remove the duplicate `spring-boot-autoconfigure` and the
redundant explicit `spring-amqp` dependency.
**Done when:** `docker compose ps` shows renewal-consumer **healthy**; both modules
build; the strict-consumer-health default in `scripts/verify.sh` is flipped ON
(a tightening, per [G7](invariants.md#g7)).

<a id="r2"></a>
### [x] R2 — Config truthfulness
**Scope:** `docker-compose.yaml`, `.env.example`, both `application.yaml`s.
Producer env: dotted names (`RABBITMQ.EXCHANGE`, `APP.TIMEZONE`, …) → underscore
relaxed-binding names. Delete dead keys: `rabbitmq.host/port/username/password` (both
services), `payment.provider.*` (until [R8](#r8) revives it with a consumer). Fix the
broken volume defaults: `.env.example`'s `POSTGRES_VOLUME=pg_data` names a volume
`docker-compose.yaml` never declares (first-time `docker compose up` fails), and the
RabbitMQ data volume is hardcoded to host path `/payfold/rabbitmq`.
**Done when:** every remaining `application.yaml` key appears in the
[config truth table](architecture.md#configuration-truth-table) as alive; stack boots;
verify.sh green.

<a id="r3"></a>
### [x] R3 — Test infrastructure + CI upgrade
**Scope:** both modules + `.github/workflows/build.yml`.
Add Maven wrapper; `spring-boot-starter-test` + Testcontainers (postgres, rabbitmq);
one context-load/smoke test per service (consumer's exercises listener wiring against
real containers). CI switches from bare `mvn` to `./mvnw verify`.
**Done when:** `./mvnw verify` green locally and in Actions.

<a id="r4"></a>
### [x] R4 — Message contract v1: cross-midnight idempotency
**Scope:** producer payload SQL, `RenewalRequested`, `BillingService`; contract section
added to [architecture.md](architecture.md).
Payload gains `event_id` (outbox row id), `idempotency_key` (`sub-<id>|<due_date>`),
`due_date`, `period_start`, `period_end`, `occurred_at`. Consumer uses payload values
**only** — the `LocalDate.now()` fallback is removed (missing fields → error path, not
guessing). `charge.due_date` gets the actual due date, not `period_end`.
**Done when:** an integration test consumes the same message twice with the clock past
midnight and finds exactly one invoice/charge/payment; [G2](invariants.md#g2) →
HELD; decision entry for contract v1; verify.sh same-day probe still green.

<a id="r5"></a>
### [x] R5 — Make the DLQ reachable
**Scope:** consumer listener config + `RabbitTopology`.
`spring.rabbitmq.listener` retry with backoff and a bounded cap;
`default-requeue-rejected: false` after exhaustion; add
`x-dead-letter-routing-key: dlq` to the main queue (queue args are immutable — document
the recreate, or introduce a v2 queue name).
**Done when:** a poison message lands in `billing.renewals.dlq` within 30s while the
listener keeps processing good messages; verify.sh gains a `--poison` probe (publish a
malformed message via the management API, assert DLQ depth 1, drain it);
[G5](invariants.md#g5) → HELD.

<a id="r6"></a>
### [x] R6 — Publisher confirms
**Scope:** `OutboxPublisher`, `RabbitConfig`, publishStep.
Correlated publisher confirms; a row's `published_at` is set only after broker confirm;
unconfirmed rows are naturally re-picked by the next page.
**Done when:** a test proves unconfirmed rows are retried; the at-least-once window is
documented in [invariants.md](invariants.md) under [G1](invariants.md#g1)/[G8](invariants.md#g8).

<a id="r7"></a>
### [x] R7 — Competing-producer safety
**Scope:** publishStep SQL + scheduler.
`SELECT ... FOR UPDATE SKIP LOCKED` on the publish page; scheduler guard via Postgres
advisory lock (or ShedLock) so two producer instances can run.
**Done when:** a concurrency test with two simultaneous publishers shows each outbox row
published exactly once; [architecture.md](architecture.md) scaling note updated.

<a id="r8"></a>
### [x] R8 — Mock PSP with a failure path
**Scope:** compose (+WireMock container), `BillingService`, revived `payment.provider.*`.
Consumer calls the mock over HTTP (baseUrl + timeout from config); provider
failure/timeout → `payment.status = 'failed'`, invoice/charge **not** finalized,
subscription not advanced; failure rate configurable via env.
**Done when:** failure-path integration test passes; verify.sh assertion changes from
"all succeeded" to "succeeded + failed == expected" (tightening: it now must also
assert failed count matches the configured rate ± tolerance).

<a id="r9"></a>
### [x] R9 — Observability
**Scope:** both services.
Replace every `System.out.println` with SLF4J; Micrometer counters
(`outbox_inserted_total`, `outbox_published_total`, `renewals_processed_total{outcome}`)
and job/listener timers; expose the prometheus endpoint.
**Done when:** metric names are documented in [architecture.md](architecture.md);
verify.sh cross-checks `outbox_published_total` against the DB count.

<a id="r10"></a>
### [x] R10 — Async job launch
**Scope:** `RenewalJobEndpoint`, launcher config.
Task-executor-backed `JobLauncher`; `POST` returns immediately with the execution id;
a `@ReadOperation` reports execution status.
**Done when:** trigger returns in <1s regardless of scale; verify.sh switches from a
long-blocking POST to trigger-then-poll-status.

<a id="r11"></a>
### [x] R11 — Scale the scan and publish
**Scope:** `RenewalJobConfig`.
Replace the single-transaction `INSERT...SELECT` with keyset-paginated chunks; batch or
pipeline the publish side (today: one synchronous send per message).
**Done when:** a run against 1M due rows completes within container memory limits;
duration and heap recorded in [quality.md](quality.md).

<a id="r12"></a>
### [x] R12 — Seed & load story
**Scope:** `seed-data-gen`, README, [architecture.md](architecture.md).
Parameterize seed size (`SEED_CUSTOMERS` env); delete dead `SubscriptionSeeder.java`;
add a load-test script; replace the bare 10M claim with measured-rate + extrapolation math.
**Done when:** a documented run at ≥100k due-today subscriptions passes verify.sh; README
states the measured rate.

<a id="r13"></a>
### [ ] R13 — Recurring entropy pass *(repeat roughly every 5 completed items)*
Dead code/config sweep; doc-drift check of [architecture.md](architecture.md) against
the code; full re-grade of [quality.md](quality.md); prune stale roadmap notes.
**Done when:** the checklist above is completed and quality.md's re-grade date is
updated. **No behavior changes allowed** in this session type.

*Last run: 2026-08-08 (after the dunning epic, R30–R32, R34, R35 — 5th run).*

<a id="r14"></a>
### [x] R14 — Migrate to Testcontainers 2.x
**Scope:** both module poms + test imports.
Docker Engine 29 (min API 1.44) rejects the API-1.32 fallback in Testcontainers
1.21.x's shaded docker-java; tests pass locally only via a machine-local
`~/.docker-java.properties` pin (`api.version=1.44`, noticed during [R3](#r3)). CI
runners will hit the same wall when they adopt Engine 29. Testcontainers 2.x renames
artifacts (`testcontainers-postgresql`, `-rabbitmq`, `-junit-jupiter`) and needs a
Spring Boot version with Testcontainers 2 support.
**Done when:** both suites are green on Testcontainers 2.x with the
`~/.docker-java.properties` pin deleted.

<a id="r15"></a>
### [x] R15 — Unroutable-message detection (publisher returns)
**Scope:** producer `RabbitConfig`, `OutboxPublisher`, application.yaml.
A publisher confirm only means the exchange accepted the message: if no queue is
bound (e.g. the consumer never declared topology), the message is confirmed and
silently dropped. Enable `publisher-returns` + `mandatory` and treat a returned
message as unconfirmed so its outbox row stays unpublished. Mind the
correlation subtlety: a returned message is also ack'ed, so the return must win.
**Done when:** a test publishing to a binding-less exchange keeps the row's
`published_at` NULL; [architecture.md](architecture.md) documents the semantics.

<a id="r16"></a>
### [x] R16 — Month-end clamp days break "due today" seeding
**Scope:** `seed-data-gen`, `ScanKeysetPaginationTest`.
`renewed_at + INTERVAL '1 month'` can never land on a month-end day the previous
month lacks (Postgres and java.time both clamp: Jun 30 + 1 month = Jul 30). On
Mar 29–31 (non-leap), May 31, Jul 31, Oct 31, and Dec 31 the seeder cannot construct
due-today subscriptions, so `verify.sh`'s "outbox contains renewals due today" check
and the keyset-scan test's due-today seeds both go red. Pre-existing since the seeder
was written; surfaced during [R11](#r11)'s test design.
**Done when:** seeding (and the test seed) constructs due-today rows on every
calendar day — e.g. via a year-interval plan for clamp days or an injectable clock —
and a test covers a clamp date.

<a id="r17"></a>
### [x] R17 — verify.sh: poison probe's main-queue check races the management API
**Scope:** `scripts/verify.sh` only.
The "main queue empty after poison message" assertion reads the management API's
`messages` counter exactly once, immediately after the DLQ-depth poll succeeds. That
counter refreshes on a ~5s stats interval, so the single read can return the
pre-dead-letter value while the queue is actually empty — the sibling "DLQ empty
after poison probe" check already polls for exactly this reason ([R5](#r5)). First
observed failing during [R12](#r12)'s 100k run (2026-07-21): reported depth=1 while
a live query showed `messages=0, ready=0, unacked=0`; the immediately following
DLQ-drain steps passed. Converting the read to a bounded poll fixes the measurement
race without weakening the asserted condition, so it is [G7](invariants.md#g7)-compatible.
**Done when:** the check polls with a bounded timeout like its DLQ siblings and a
100k-scale verify run passes it.

<a id="r18"></a>
### [x] R18 — Deploy epilogue: publish versioned multi-arch images to GHCR
**Scope:** sanctioned by [D11](decisions.md#d11). New `db-migrations/Dockerfile` and
`seed-data-gen/Dockerfile` (with a strict-exit `run-seeder.sh` — a Job must fail
loudly); `$BUILDPLATFORM`-pinned build stages in both service Dockerfiles; compose's flyway and seed-data services build those images instead of
bind-mounting host paths; new `.github/workflows/publish.yml`; `scripts/verify.sh`;
`docs/architecture.md` + `docs/quality.md`.
A manually pushed `vX.Y.Z` git tag publishes `payfold-renewal-producer`,
`payfold-renewal-consumer`, `payfold-migrations` (Flyway + baked `V*.sql`,
configured via `FLYWAY_URL`/`FLYWAY_USER`/`FLYWAY_PASSWORD`, run-to-completion,
exit 0 = success, re-run = no-op), and `payfold-seed-data-gen` (seed source + JDBC
driver + name data baked, same Job pattern) to `ghcr.io/diblan/` — each tag an
immutable linux/amd64 + linux/arm64 manifest list, never `latest`.
**Done when:** all four images multi-arch-build locally via buildx;
`scripts/verify.sh` gains a migrations-image no-op-Job re-run check against the
live stack (a tightening, [G7](invariants.md#g7)); architecture.md documents the
image catalogue, env contracts, and tag scheme with the truth-table/ports/health
coupling surface unchanged; the publish workflow itself is exercised on the next
manual tag push (out of session scope per the no-CI-push policy).

<a id="r19"></a>
### [x] R19 — Platform-flagged contract debt: PSP wire schema + amd64-only WireMock
The platform repo's P8 onboarding had to author its own stub of the mock PSP
because the **response** schema of `POST /psp/charges` is not documented contract
(its P8 notes flag this to payfold explicitly), and its multi-arch audit found
compose's `wiremock/wiremock:3.13.2-alpine` is **amd64-only** (verified 2026-07-26:
the plain `3.13.2` variant publishes amd64+arm64+arm/v7).
**Scope:** `docs/architecture.md` (mock-PSP section + coupling surface),
`docker-compose.yaml` (one image line), consumer integration test image ref if it
names the alpine variant.
**Done when:** architecture.md documents the full PSP request *and* response
schema exactly as the templates render (the wire contract a third party can stub
from); the deploy-artifacts coupling note also records `/actuator/health/liveness`
as platform-consumed (Spring Boot auto-enables probe groups on Kubernetes; the
platform's livenessProbe already uses it); compose and the consumer test run the
multi-arch WireMock variant; verify.sh green.

<a id="r20"></a>
### [x] R20 — Consumer scaling levers: measured, not assumed
The consumer is the binding constraint (~48/s sustained, [R12](#r12)) and its two
scaling levers are untested: listener concurrency (in-process) and multiple
consumer instances (G2's constraint-based idempotency makes N parallel consumers
safe — the platform's KEDA showpiece already scales pods 1→5 on queue depth, but
payfold has never measured what that buys).
**Scope:** consumer `application.yaml` (concurrency as env-tunable config),
`docker-compose.yaml` (scale-safe consumer: fixed `container_name` and host-port
mapping both block `--scale renewal-consumer=N` — resolve without breaking
verify.sh's consumer checks), `docs/quality.md`, README math.
**Done when:** documented ≥100k runs measure drain rate at baseline, raised
listener concurrency, and ×3 instances; rates land in quality.md "Measured scale
runs" and the README extrapolation; the architecture honesty table row for
consumer scaling flips to measured; verify.sh green at scale ([R17](#r17)'s poll
fix should land first or ride along — its acceptance needs exactly this run).

<a id="r21"></a>
### [x] R21 — Grafana dashboards as code ([D12](decisions.md#d12))
**Scope:** `docker-compose.yaml` (Prometheus + Grafana containers; RabbitMQ's
built-in prometheus plugin exposed for queue/DLQ depth), provisioning files +
dashboard JSON checked in, `.env.example`, docs.
The pipeline story in one provisioned dashboard: outbox insert/publish rates,
`renewals_processed_total` by outcome, listener timer, queue + DLQ depth, drain
rate — consuming only [R9](#r9)'s documented metric names. Zero manual clicks:
datasource and dashboards provisioned from files. Panel queries written to be
reusable by the platform repo's Grafana (which covers autoscaling; this covers
pipeline internals).
**Done when:** a fresh `docker compose up` serves the dashboard immediately;
metric names used are exactly the documented contract; docs updated (G6); how the
observability containers affect verify.sh decided at execution under
[G7](invariants.md#g7) (tighten or leave, never loosen).

<a id="r22"></a>
### [x] R22 — Scripted chaos demo
**Scope:** `scripts/chaos-demo.sh`, README section; no service changes.
One command, scene-based, each scene *asserting* the invariant it demonstrates
(not just showing it): poison → DLQ within 30s while good messages keep flowing
([G5](invariants.md#g5)); kill the consumer mid-drain → backlog accumulates →
restart → drains with zero loss (DB counts prove it); scale consumers ×N → drain
rate multiplies ([R20](#r20)'s numbers make the claim honest); broker restart →
confirm-gated publishing re-picks unpublished rows ([G1](invariants.md#g1)).
Honesty guardrail from the direction discussion: auto-respawn is orchestration
(non-goal) — the demonstrable claim is "worker dies → nothing lost → backlog
drains on recovery".
**Done when:** the demo runs green end-to-end on a fresh stack with the
[R21](#r21) dashboard telling the same story live; README documents how to run it.

<a id="r25"></a>
### [x] R25 — Publisher channel discipline under a slow broker
**Scope:** producer `OutboxPublisher` / `RabbitConfig` (cache/confirm settings).
Observed 2026-07-26 (R21 session, cold boot under image-pull load): with the
broker slow to confirm, the pipelined page publish piled up channels — spring-
rabbit creates a new channel per concurrent send when cached channels are
awaiting confirms — until RabbitMQ's `channelMax` (2047) threw
`AmqpResourceNotAvailableException`, the page had zero confirms, and the job
FAILED on the zero-progress rule. The safety net held ([G1](invariants.md#g1):
outbox rows stayed unpublished; the next trigger re-picked and published all
15k cleanly), but a degraded broker shouldn't cost a job failure. Likely shape:
bound in-flight sends per page (or cap/reuse channels: `channelCacheSize` +
`channelCheckoutTimeout` turn the cache into a bounded pool) so the page
publishes within a fixed channel budget; keep the loud zero-progress failure.
**Done when:** a test proves the publish page never exceeds a bounded channel
count under delayed confirms; a slow-confirm scenario completes without
`channelMax` exhaustion; the at-least-once semantics and zero-progress rule are
unchanged.

<a id="r23"></a>
### [x] R23 — SEPA mock-bank: async settlement ([D13](decisions.md#d13), design [D15](decisions.md#d15)/[D17](decisions.md#d17)) *(epic — split 2026-07-26 into R23a–R23f below; check when all six are checked)*
**Done when (epic-level):** a renewal is only `succeeded` after asynchronous
confirmation — for **both** payment methods ([D17](decisions.md#d17)): SDD via
the mock bank, cards via a sync auth verdict + async settlement on the same
spine; chaos parameters demonstrably shift outcomes; verify.sh models the async
settlement deterministically (the [R8](#r8) recomputable-rule precedent).
Sub-items execute strictly top-down, one per session. `renewal.requested` stays
at v1 (additive only, [G8](invariants.md#g8)); the settlement message is a new
internal contract starting at v1 ([D15](decisions.md#d15)).

<a id="r23a"></a>
### [x] R23a — Mock bank service, standalone
**Scope:** new `mock-bank/` (Python 3 + FastAPI, [D15](decisions.md#d15)),
Dockerfile (multi-arch-buildable, [R18](#r18) precedent), compose service +
healthcheck, CI job for the Python suite; no Java changes.
`POST /collections` accepts a submission (collection id, amount in integer cents
+ currency per [G4](invariants.md#g4), debtor IBAN, mandate reference, due
date); the outcome — `settled`, failed with an ISO reason (AM04 insufficient
funds, AC04 closed account, MD01 no mandate), or settle-then-chargeback (MD06)
— derives **deterministically from the debtor IBAN** ([R8](#r8) precedent);
after the instance's configured delay profile (fixed/fast for verify, visible
for demo), it POSTs a settlement notification (bank id, notification id,
collection id, outcome, reason) to a configured webhook URL, signed
HMAC-SHA256 with a per-bank shared secret, retrying on non-2xx with backoff and
a bounded attempt cap — then gives up loudly (log + metric), never silently.
Instance config (bank id, delay profile, secret) is designed for scheme reuse —
a `card` scheme joins the same image in [R23f](#r23f) ([D17](decisions.md#d17)).
**Done when:** pytest covers the IBAN rule engine, HMAC signing, and bounded
retry; a curl'd submission with a rule-bearing IBAN delivers a signed webhook to
a test sink after the configured delay; the compose service is healthy on a
fresh `up`; verify.sh gains a mock-bank health check (a tightening,
[G7](invariants.md#g7)); [quality.md](quality.md) gains a `mock-bank` module row.

<a id="r23b"></a>
### [x] R23b — Payment methods: the SDD cohort submits, cards keep flowing
**Scope:** new migrations ([G3](invariants.md#g3)): customers gain
`payment_method` (`card` | `sdd`, seeded mix env-tunable per
[D17](decisions.md#d17)) and, for SDD, IBAN + mandate reference + country
(rule-bearing IBAN share env-tunable); `payment.status` grows `submitted`,
payment rows gain bank id + collection id. `BillingService` routes by method:
**card → the existing synchronous PSP path, byte-for-byte untouched**; sdd →
submit a collection to the bank — payment `submitted`, invoice/charge **not**
finalized, subscription **not** advanced, message ACKed. The mock PSP does
**not** retire here ([D17](decisions.md#d17) — that's [R23f](#r23f)); bank base
URL/secret env → config truth table ([G6](invariants.md#g6)).
**Done when:** a routing integration test proves each method takes its path;
consuming an SDD renewal ends `submitted` with nothing finalized and no
redelivery; duplicate delivery still yields exactly one submitted payment
([G2](invariants.md#g2)); verify.sh keeps [R8](#r8)'s card assertions
**verbatim** ([G7](invariants.md#g7) untouched) and adds exact SDD assertions:
every SDD renewal has exactly one payment, all `submitted`, zero finalized
(exact counts by seeded mix).
**Note:** until [R23c](#r23c), SDD payments park in `submitted` by design while
cards settle as today — honest intermediate state (see phase note above).

<a id="r23c"></a>
### [x] R23c — Close the loop: webhook receiver, settlement inbox, queue, listener
**Scope:** new migration: `settlement_inbox` (bank id, notification id, raw
payload, received/published timestamps; **unique (bank id, notification id)** —
the row doubles as its own outbox via `published_at`, [D15](decisions.md#d15));
payment-service gains `POST /webhooks/bank/{bankId}`: HMAC failure → 401,
unparseable → 400 (the bank's bounded retry then gives up loudly), valid →
inbox insert in one transaction, duplicate insert → no-op 200; a relay
publishes unpublished inbox rows to a new settlements queue (+DLQ, [D4](decisions.md#d4)
topology) as the normalized bank-agnostic settlement contract v1 (notification
id = idempotency key); a settlement listener finalizes: `settled` → charge
settled, invoice paid, subscription advanced; `failed` → terminal + reason;
Micrometer `settlements_processed_total{outcome}` + dashboard panels
([D12](decisions.md#d12)); architecture.md message-flow section ([G6](invariants.md#g6)).
**Done when:** integration tests prove (a) duplicate webhook → one inbox row,
one finalization; (b) redelivered settlement message → no double finalize
([G2](invariants.md#g2)); (c) poison settlement → DLQ within the bound while
good ones keep flowing ([G5](invariants.md#g5)); verify.sh closes phase 2: after
bank-delay + drain, **zero** SDD payments stuck `submitted`, per-outcome exact
counts match the IBAN-rule prediction, and reconciliation holds — every inbox
row processed-or-DLQ'd, every terminal SDD payment traces to an inbox row. A
renewal is `succeeded` only after asynchronous confirmation — the epic's
headline now holds for the SDD cohort (universal at [R23f](#r23f)). Tag proposal
expected (the SDD loop is closed).

<a id="r23d"></a>
### [x] R23d — Chargebacks + chaos profiles that shift outcomes
**Scope:** new migration: `payment.status` grows `charged_back` (+ reason);
mock-bank emits MD06 **after** a settled notification, deterministic from the
IBAN rule with a configurable chargeback lag; the subscription **stays
advanced** and nothing compensates — reacting is dunning ([D16](decisions.md#d16));
bank delay-profile envs demonstrably shift the live picture (slow profile →
visible `submitted` backlog on the [R21](#r21) dashboard); a new chaos-demo
scene ([R22](#r22)) asserts the chargeback invariants.
**Done when:** an integration test proves a settled payment receiving MD06 ends
`charged_back` with invoice marked and subscription untouched, idempotent under
webhook redelivery; verify.sh gains exact chargeback-count assertions from the
IBAN rules ([G7](invariants.md#g7)); the demo scene runs green; the dashboard
outcome split includes chargebacks. Re-triggers [R24](#r24).

<a id="r23e"></a>
### [x] R23e — Multi-bank: per-country routing, one slow bank
**Scope:** compose runs ≥2 instances of the same mock-bank image with different
profiles (one fast, one slow — [D15](decisions.md#d15)'s "bank N+1 is a compose
entry" claim, proven); consumer gains a bank registry (customer country →
bank base URL + shared secret); seeder distributes countries; per-bank metric
label + a per-bank dashboard row (settlement latency, outcome split);
architecture.md bank-registry + truth-table update ([G6](invariants.md#g6)).
**Done when:** a routing test proves country → bank is deterministic; verify.sh
asserts per-bank attribution (each bank's inbox rows match its routed share,
[G7](invariants.md#g7)); the dashboard visibly shows the slow bank lagging the
fast one on the same load; the [R22](#r22) demo gains a slow-bank backlog-drain
beat.

<a id="r23f"></a>
### [x] R23f — Cards join the async spine; WireMock retires ([D17](decisions.md#d17))
**Scope:** the mock-counterparty service grows a `scheme` config
(`sepa_core` | `card`): a card instance answers submission with a
**synchronous auth verdict** — mirroring the real card network's auth round
trip; declines deterministic from the card token ([R8](#r8)'s original trick
coming home) — and delivers the **settlement** webhook on a fast profile with
card decline vocabulary (insufficient funds, do-not-honor, …); the
settle-then-chargeback rule applies to cards too ([R23d](#r23d)'s machinery,
card-flavored reason). Consumer card path reshapes: auth decline → terminal
`failed` immediately; authorized → `submitted`, finalization via the same
inbox → queue → listener spine. The WireMock mock PSP and `payment.provider.*`
config retire — **flag loudly**: the platform repo's P8 onboarding stubs the
PSP and consumes its env ([R19](#r19)); the session summary must call out every
platform-facing removal and addition; architecture.md wire-contract section
replaced by the counterparty contract ([G6](invariants.md#g6)).
**Done when:** integration tests prove the sync-decline and async-settle card
paths, idempotent under redelivery ([G2](invariants.md#g2)); verify.sh reshapes
[R8](#r8)'s card assertions from rate ± tolerance to **exact per-outcome
counts** (a tightening, [G7](invariants.md#g7)) and extends reconciliation +
zero-stuck-`submitted` to **all** payments; the dashboard outcome split gains a
per-method dimension; re-triggers [R24](#r24). Tag proposal expected. Epic
checkbox closes with this item.

<a id="r24"></a>
### [ ] R24 — README screen recording
**Scope:** README + a recording asset/link; no code.
The primary interviewer-facing artifact: a 3–5 minute recording of the
[R22](#r22) chaos demo with the [R21](#r21) dashboard visible — interviewers
don't clone repos. The demo script makes recording reproducible, so re-recording
after [R23](#r23) reshapes the flow is cheap and expected.
**Done when:** the README embeds (or links) the recording near the top and every
claim shown matches the measured numbers in quality.md/README.
*Re-record note (2026-08-01, [R26d](#r26d)): the demo now ends with the dunning
scene — record after R26d so the recording covers recovery and cancellation.*

<a id="r27"></a>
### [x] R27 — chaos-demo scene 2: backlog precondition races the management API
**Scope:** `scripts/chaos-demo.sh` only.
Scene 2's "poison injected mid-drain with good-message backlog" precondition
reads the management API's `messages` counter exactly once right after the
outbox publishes. That counter refreshes on a ~5s stats interval, so the single
read can return a pre-publish `0` while the queue actually holds the scene's
backlog — the same measurement race [R17](#r17) fixed inside `verify.sh`
(first observed here 2026-07-26 during [R23e](#r23e)'s acceptance run: depth=0
reported, the immediately following poison-routing and drain steps all passed,
and a full re-run was clean). Converting the read to a bounded
poll-until-nonzero fixes the measurement without weakening the asserted
condition; if polling shows the backlog can genuinely drain before any sample,
the scene's cohort size (not the poll) is the lever.
**Done when:** the precondition polls with a bounded timeout like its
verify.sh siblings and a full `chaos-demo.sh --auto` run passes it on a fresh
stack.

<a id="r28"></a>
### [x] R28 — Webhook exhaustion strands submitted payments (no re-delivery path)
**Scope:** design first — likely consumer-side (a scheduled sweeper that
re-queries counterparties for stale `submitted` payments via
`GET /collections/{id}`), possibly with a counterparty-side resend endpoint.
When a counterparty exhausts its bounded webhook retry (loud give-up by
design, [R23a](#r23a)), the collection's outcome exists at the bank but never
reaches the consumer: the payment parks in `submitted` forever. Surfaced
2026-07-26 by [R23f](#r23f)'s acceptance: chaos scenes that hold the consumer
down longer than the retry envelope stranded their cohorts. Mitigated same
day by widening the shared envelope (`BANK_WEBHOOK_RETRY_*`, 8 × 2s-base ≈
4 min) past any demo outage — a real system needs a recovery story, not a
longer fuse: real SEPA reporting is pull-shaped (EBICS files) precisely so
missed pushes cannot strand state. The same gap has a second face: pending
deliveries are in-memory asyncio tasks, so RECREATING a counterparty
container (e.g. reprofiling delays via `docker compose up -d`) silently
drops every not-yet-fired notification — 1638 collections stranded that way
during R23f acceptance before the demo stopped reprofiling mid-scene. Pairs
naturally with the dormant reconciliation tables and/or [R26](#r26)'s
lifecycle work.
**Done when:** a payment whose settlement webhook was fully exhausted
demonstrably reaches its bank-side terminal state without manual
intervention, bounded-time; verify.sh models the recovery deterministically.

<a id="r26"></a>
### [x] R26 — Dunning: failed collections get a lifecycle ([D16](decisions.md#d16)) *(epic — split 2026-08-01 into R26a–R26d below; all four checked 2026-08-01)*
**Scope (promotion-level):** consumes [R23](#r23)'s terminal outcomes — no new
service, no notification channels. Per-reason retry policy (AM04 insufficient
funds retriable on a schedule; AC04 closed account and MD01 no mandate are not),
a `past_due` grace lifecycle on the subscription, bounded attempts ending in
cancellation; schema via new migrations ([G3](invariants.md#g3)).
**Design spine (pre-decided):** every terminal reason maps to exactly one
dunning class — `retriable` (AM04, `insufficient_funds`), `hard_fail` (AC04,
MD01, `do_not_honor`), `dispute` (MD06, `fraud_dispute`) — in consumer config,
not the DB (policies are code-shaped, outcomes are data-shaped). Re-collections
are consumer-internal: a new submission through the existing
`BillingService` → counterparty → settlement spine with collection id
`sub-<id>|<due_date>|a<attempt>` — never a new `renewal.requested` message
([G1](invariants.md#g1) stays about renewals; both contracts stay at v1,
[G8](invariants.md#g8)). One env-tunable grace deadline per class in config,
a single `grace_until` column in the DB. Dunning touches subscription `status`
only, never period math (consistent with [R23d](#r23d) keeping the advance).
Retry schedule and deadlines are env-tunable seconds (`DUNNING_*`), fast for
verify, visible for demo. The dunning sweeper reuses the [R7](#r7)
advisory-lock scheduled-task pattern and stays separate from [R28](#r28)'s
recovery sweeper: dunning reacts to RECEIVED signals, recovery to MISSING
ones — a stranded `submitted` payment has no terminal reason and is invisible
to dunning by design (the sweepers compose, neither depends on the other).
**Done when (epic-level):** a retriable failed collection demonstrably
re-collects on schedule and settles or exhausts into cancellation; a chargeback
moves the subscription through the grace lifecycle instead of being a dead-end
fact; verify.sh models the retry outcomes deterministically.

<a id="r26a"></a>
### [x] R26a — Terminal outcomes enter the grace lifecycle (no retries yet)
**Scope:** new migration: `subscription` gains `grace_until TIMESTAMPTZ` and
`past_due` joins the status vocabulary (V1 already documents `canceled`);
consumer config gains the reason→class map and per-class grace seconds; the
settlement listener, on terminal `failed`/`charged_back`, applies the class:
any class → subscription `past_due` with a deadline (chargebacks per
[D16](decisions.md#d16) stop being dead-end facts; the [R23d](#r23d) "stays
advanced" period math is untouched — only `status` moves). `BillingService`'s
synchronous card-decline branch applies the same map — a card auth decline is
a terminal failure too, and [R26c](#r26c)'s cancellation matrix counts the
card 98/99 cohorts. Dashboard gains a `past_due` depth panel.
**Done when:** integration tests prove each reason class moves an `active`
subscription to `past_due` exactly once, idempotent under settlement
redelivery ([G2](invariants.md#g2)); verify.sh asserts exact `past_due` counts
predicted from the IBAN/token rules ([G7](invariants.md#g7) tightening); a
redelivered chargeback still yields one `past_due` transition.

<a id="r26b"></a>
### [x] R26b — Scheduled re-collection for retriable failures
**Scope:** `payment` rows gain `attempt` (int, default 1, part of a new unique
key with the collection id family); dunning sweeper (advisory-locked, scaled
schedule `DUNNING_RETRY_DELAY_SECONDS`) picks `past_due` subscriptions whose
latest payment is `failed` with a `retriable` reason and submits attempt N+1
through the normal spine; mock-bank gains ONE new deterministic rule so
recovery is testable: IBAN/token suffix `95` = fail AM04 (/
`insufficient_funds`) on attempt 1, settle on attempt ≥ 2 — attempt-indexed
off the stored collection history, same recomputable-rule spirit as
[R8](#r8)/[R23a](#r23a). Settled retry → subscription back to `active`,
`past_due` cleared. The rule change alters the published mock-bank image —
tag proposal expected.
**Done when:** a suffix-95 customer demonstrably fails, re-collects after the
scaled delay, settles, and returns to `active` — end to end in verify.sh with
exact counts (95-cohort = recovered, 99-cohort = still failing); duplicate
sweeper ticks never double-submit an attempt (constraint-keyed,
[G2](invariants.md#g2) style); `hard_fail` and `dispute` reasons are provably
never retried; the dunning sweeper provably ignores `submitted` rows
regardless of age ([R28](#r28) boundary).

<a id="r26c"></a>
### [x] R26c — Bounded exhaustion and grace expiry end in cancellation
**Scope:** `DUNNING_MAX_ATTEMPTS` (retriable path) and grace-deadline
enforcement (all classes): the sweeper cancels subscriptions whose retriable
attempts exhausted (suffix 99 never settles) or whose `grace_until` passed
without recovery (`hard_fail`, `dispute`); cancellation uses V1's documented
`canceled` status, is terminal and idempotent; dashboard outcome split gains
cancellations.
**Done when:** verify.sh predicts exactly which seeded customers end
`canceled` vs re-`active` from suffix arithmetic alone (99 →
exhausted-canceled, 95 → recovered, 98/97 + card 98 → grace-expired-canceled,
96 + card 96 → dispute-grace-canceled) and asserts the counts; a canceled
subscription is never re-collected; re-running the sweeper is a no-op
([G2](invariants.md#g2)).

<a id="r26d"></a>
### [x] R26d — The dunning story: chaos scene + README
**Scope:** `scripts/chaos-demo.sh` gains a dunning scene: the suffix-95 cohort
visibly fails, goes `past_due` on the dashboard, recovers on retry; the 99
cohort exhausts into cancellation — both asserted, not narrated ([R22](#r22)
rule); README + architecture.md dunning section; [R24](#r24) re-record note.
**Done when:** the scene runs green on a fresh stack `--auto`; every claim in
the README dunning paragraph matches an assertion in the scene or verify.sh.

<a id="r29"></a>
### [x] R29 — Consumer integration polls abort on missing rows instead of retrying
**Scope:** both consumer integration test classes; no production code.
Awaitility's `untilAsserted` retries only on `AssertionError`, but every DB poll
in the consumer suite reads via `queryForObject`, which throws
`EmptyResultDataAccessException` while the awaited row does not exist yet — so a
poll that fires before the consumer has processed the message aborts the await
instantly instead of retrying. Latent since [R23c](#r23c) (locally the consume
always beat the first poll); first fired on the settlement suite's first-ever
Actions run (2026-07-26, build run 30213487837):
`redeliveredSettlementMessageDoesNotDoubleFinalize` died ~1 s into its 30 s
window when a transient broker EOF on the runner delayed consumption past the
first poll. The [R17](#r17)/[R27](#r27) measurement-race class, test-suite
flavor: a missing row is "not yet", not "fail now".
**Done when:** every await in both classes tolerates a missing row as a
retriable state (`ignoreExceptionsInstanceOf(EmptyResultDataAccessException)`)
with asserted conditions and timeouts unchanged (timeouts still fail loudly with
the last miss as cause); the consumer suite is green; verify.sh untouched.

<a id="r31"></a>
### [x] R31 — Keyset-scan payload assert contradicts its own clamp-day seed
**Scope:** `ScanKeysetPaginationTest` only; no production code.
[R16](#r16) made the test's due-today seeding clamp-day-safe: on days with no
`+1 month` preimage the seed helper switches to a year-interval plan, and the
`period_end` assertion follows `seedInterval` — but the payload `interval`
assertion still hardcoded `"month"`. First fired on the 2026-07-31 Actions run
(build 30667459146, the first CI run to land on a clamp day since the test
exists): the seeded year plan echoed `interval: "year"` and the field check
went red while every other producer test passed. Local runs on non-clamp days
are green — the red is calendar-dependent, not environmental.
**Done when:** the payload `interval` assertion compares against `seedInterval`
(the payload must echo the plan the seed chose, on both branches); the
producer suite is green; verify.sh untouched.

<a id="r32"></a>
### [x] R32 — High-rate consumption saturates the mock counterparties; retry storms DLQ good renewals
**Scope:** design first — likely counterparty service capacity (a bare
multi-worker uvicorn is NOT sufficient alone: collection records and pending
delivery tasks are per-process state, so worker scaling needs a worker-safe
design) and/or consumer-side submit backpressure and timeout budget; decided
at execution, with a decision entry if the shape crosses a non-goal.
Observed 2026-08-01 during [R30](#r30)'s concurrency-8 100k run: the consume
burst drives each single-worker FastAPI counterparty past its event-loop
budget — cardnet absorbs ~80% of submissions PLUS one signed webhook delivery
per settlement on the same loop — so response latency crosses the consumer's
2 s `bank.timeout-ms`, `BankSubmissionException` rides the bounded listener
retry, all eight threads park in exponential backoff, and drain collapses
(measured: 64/s in the first minute, then 0–26/s oscillation with whole
minutes at zero; 19,797 of 100,000 consumed in 44 min). Exhausted retries
dead-letter GOOD renewals — 654 in the DLQ, 646 payments stranded `pending` —
[G5](invariants.md#g5) treats a saturated counterparty like poison. The
stale-`submitted` backlog meanwhile ages past `RECOVERY_STALE_AFTER_SECONDS`,
so the [R28](#r28) sweeper adds GET load to the already-saturated banks (its
own read timeouts logged) — a feedback loop. The retired pre-[R23f](#r23f)
524/s figure was measured against WireMock (threaded Java, no outbound work);
the async-era ceiling at high concurrency is the counterparty mock, not the
consumer. Baseline single-consumer drain (~62/s) is unaffected.
**Done when:** the [R20](#r20) lever recipe (`CONSUMER_LISTENER_CONCURRENCY=8`,
`SEED_CUSTOMERS=100000`) completes `verify.sh --no-up --timeout 3600` green
with zero dead-lettered renewals; the measured drain and end-to-end numbers
land in quality.md "Measured scale runs"; the architecture honesty-table row
this item blocks is updated from measured-collapse to measured-throughput.

<a id="r30"></a>
### [x] R30 — Re-measure the drain story on the async spine
**Scope:** measurement + docs only (README, quality.md "Measured scale runs",
architecture.md honesty table); no behavior changes.
Every published consumer number (~48/s sustained from [R12](#r12); 524/s at
listener concurrency 8 and 105/s at ×3 replicas from [R20](#r20)) predates
[R23f](#r23f): a card consume then settled synchronously inside the handler, so
"renewals drained" meant "renewals finished". Since R23f a consume is a fast
auth + an async settlement, and the same probe measures two different
quantities: renewals-queue drain rate and end-to-end settlement completion
(every payment terminal, zero stuck `submitted`). Flagged (not re-measured) by
the 2026-07-31 R13 pass, which era-scoped the README/architecture claims.
**Done when:** quality.md gains an async-era measured-scale entry reporting
BOTH quantities for the documented 100k run and both [R20](#r20) lever
configurations; README and the architecture honesty table quote the new
numbers and drop the era note; verify.sh untouched ([G7](invariants.md#g7)).

<a id="r33"></a>
### [x] R33 — Verify the dashboard tells the truth, not just that it exists
**Scope:** `scripts/verify.sh` (+ possibly a small helper); no service changes
expected; architecture.md observability section per [G6](invariants.md#g6) if
the metric-name contract wording moves.
Origin: an external review lens — Birgitta Böckeler's memo on OpenAI's
harness-engineering article
(<https://www.martinfowler.com/articles/exploring-gen-ai/harness-engineering-memo.html>)
observes that agent-first harnesses tend to verify *internal* quality
mechanically while leaving *externally observable behavior* unverified.
Payfold's harness is strong on that axis — verify.sh drives the running stack
end to end (trigger → exact terminal states from the IBAN/token rules →
Prometheus/DB delta cross-checks → poison → idempotency re-trigger) — with one
exception: the only human-facing surface, the [R21](#r21) Grafana dashboard
([D12](decisions.md#d12)'s carve-out from the no-UI non-goal). verify.sh
asserts Grafana *serves* the provisioned dashboard (`/api/search` finds uid
`payfold-pipeline`) but never executes a single panel query. A renamed metric
or a broken PromQL edit leaves a lying dashboard behind a green verify —
exactly the gap class the memo predicts. Panels have been added by
[R21](#r21), [R23c](#r23c)–[R23f](#r23f), [R26a](#r26a), and [R26c](#r26c);
none is behavior-verified.
**Done when:** verify.sh extracts every panel query from the provisioned
dashboard JSON and asserts each is accepted by live Prometheus and returns at
least one series after the run's load (bounded poll for scrape-interval lag,
[R17](#r17) precedent; panels whose series may legitimately be absent get an
explicit allowlist, decided at execution); a deliberately broken panel query
demonstrably fails verification; a tightening per [G7](invariants.md#g7).

<a id="r35"></a>
### [x] R35 — Sweepers' immediate initial tick races the integration tests
**Scope:** `DunningSweeper` and `RecoverySweeper` `@Scheduled` annotations;
config truth-table wording ([G6](invariants.md#g6)); no schema or contract changes.
Spring fires a `fixedDelay` task's FIRST execution immediately at scheduler
startup — the test suites' 3600000 ms intervals only space *subsequent* ticks.
On the first CI run of the [R26b](#r26b) suite (2026-08-01, build 30706457574)
the consumer's single scheduler thread (shared with the 500 ms inbox relay and
the 10 s `past_due` gauge refresh) delayed `DunningSweeper`'s initial tick ~9 s
into the run, landing it inside `DunningRetryIntegrationTest`'s first test
method between the `@BeforeEach` stub wipe and the test's own stub: the tick
picked the freshly seeded fixture, POSTed to a stub-less WireMock (journaled as
unmatched), got 404 → `BankSubmissionException` → the [D20](decisions.md#d20)
rollback discarded the attempt row (the constraint design held — no orphan, no
double row), and the test's `sweepOnce()` then re-picked and submitted:
journal count 2 where the test asserts exactly 1. Local runs boot fast enough
that the initial tick fires before any fixture exists — the red is
scheduler-timing-dependent. `RecoverySweeper` has the same immediate initial
tick and the same latent exposure in its own suite.
**Done when:** both sweepers' first scheduled execution fires one full
interval after startup (`initialDelayString` = the interval property), making
a long test interval structurally silence the schedule; the consumer suite is
green; verify.sh green (the compose-level first-sweep shift is ≤ one 10 s
interval, absorbed by the existing bounded polls).

<a id="r36"></a>
### [x] R36 — Single-consumer consume cost tripled across R32/R26b (16 ms → 43 ms)
**Scope:** diagnosis first — measured facts before any fix; likely candidates
are per-request costs the R32/R26b diffs added on the consume round trip, with
the counterparty image's multiprocess metrics files
(`PROMETHEUS_MULTIPROC_DIR`, set for ALL instances including the single-worker
banks, [D19](decisions.md#d19)) the prime suspect; consumer-side additions
(V11 constraint arbitration, R26a grace calls) secondary. Design-first if the
fix changes a published image.
Observed 2026-08-01 during [R26d](#r26d)'s chaos-demo acceptance: the fresh
15k+5k scene-1 drain sustained ~22/s at listener concurrency 1 where
[R30](#r30) measured 62/s three days of commits earlier — Prometheus put the
renewal listener's mean processing time at 42–50 ms/message across the whole
drain (2026-08-01 19:35–19:49 CEST) against R30's ~16 ms. Ruled out by
live forensics: both 10 s sweeper SELECTs execute sub-millisecond at 36k
payment rows, and post-R26c re-collection churn is bounded-tiny during the
drain (~1 extra submission/s) — the [R26b](#r26b) session's "churn drags
conc-1 to ~24/s" attribution was at least incomplete, since bounded churn
shows the same rate. The system stays correct and verify.sh green; the cost
is wall-clock only (verify/demo budgets were raised to absorb it: 900 s and
1200 s).
**Done when:** the per-consume cost is decomposed with measurements (bank
round-trip vs consumer DB work vs listener overhead), the regressing
mechanism is named and either reverted/fixed (conc-1 back near its ~60/s
baseline, budgets re-tightened accordingly) or accepted with a decision entry
re-documenting the baseline; quality.md "Measured scale runs" and the README
numbers reflect whichever truth wins.

<a id="r34"></a>
### [x] R34 — Chaos-demo terminal predictions predate the 95 re-collection cohort
**Scope:** `scripts/chaos-demo.sh` only; no service changes.
[R26b](#r26b) taught verify.sh that the suffix-95 cohort terminates on its
attempt-2 `|a2` row (fails first by rule, settles on re-collection), but the
demo's scene SQLs still carry the pre-R26b predictions: a 95 customer is
predicted `succeeded` at the BASE collection key, which no longer exists as a
terminal row — a default-seed scene run mismatches (or races the ~25 s
re-collection cycle). Found by inspection at R26b close; the demo was not
re-run. Naturally lands with (or just before) [R26d](#r26d)'s dunning scene;
kept separate because it is a correctness defect in existing scenes, not new
story.
**Done when:** every scene's terminal-prediction SQL mirrors verify.sh's
95-aware form (attempt-2 key for the 95 cohort, base keys elsewhere); a full
`chaos-demo.sh --auto` runs green on a fresh default-seed stack.

<a id="r37"></a>
### [ ] R37 — Dashboard gauge and color semantics lie at N replicas
**Scope:** `observability/grafana/dashboards/payfold-pipeline.json` only; no
service changes. Found by the 2026-08-08 [R13](#r13) audit.
"Subscriptions past due (now)" reads `sum(subscriptions_past_due)`, but every
consumer replica reports the same database-global count (the gauge is not
additive — [architecture.md](architecture.md) metric table), so the stat
over-reports ×N exactly when the chaos demo's ×3 scaling scene runs; `max()`
is the truthful aggregation. The "Settlements — processed/s by outcome" color
overrides match a nonexistent `succeeded` outcome (the vocabulary is
`settled|failed|charged_back|invalid`) and are all dead anyway because the
legend renders `{{bank}} {{outcome}}`; `charged_back` — the outcome an
operator most needs to spot — has no override, and the renewals panels leave
`submitted` (the dominant async-era series) uncolored.
**Done when:** the past_due stat reads true at 1 and 3 replicas (spot-checked
against the DB count on a scaled stack); settlement/renewal color overrides
match only label values that exist, including `charged_back` and `submitted`;
[R33](#r33)'s panel-query checks stay green.

<a id="r38"></a>
### [ ] R38 — load-test.sh seeds customers that violate V9's card-token constraint
**Scope:** `scripts/load-test.sh` only.
Its ad-hoc seed inserts `customer (id, email)` alone; `payment_method`
defaults to `card` (V6) and `customer_card_token_chk` (V9) requires every
card customer to carry a token, so the very first seeded row aborts the
batch — `scripts/load-test.sh N` has been broken since V9 landed
(2026-07-26). The [R23f](#r23f) session's "a new constraint's blast radius
is the whole repo" sweep covered test fixtures but missed this script's
INSERT; first exercised (and caught) 2026-08-08 during [R36](#r36)
forensics, mitigated there with ad-hoc SQL ([R11](#r11) precedent,
deliberately uncommitted).
**Done when:** the seeded cohort satisfies V6+V9 (explicit `payment_method`
plus a non-rule `card_token`, or a deterministic mix mirroring the compose
seeder), `scripts/load-test.sh 5000` completes green against a running
stack, and the script's README/architecture mentions stay accurate.

<a id="r39"></a>
### [ ] R39 — Re-measure the 100k scaling-lever matrix post-D23
**Scope:** measurement + docs only (quality.md "Measured scale runs", README,
architecture honesty table); no behavior changes — the [R30](#r30) shape.
Every 100k lever number predates [D23](decisions.md#d23)'s counterparty stall
fix: the conc-8 157/s ([R32](#r32)) was measured with the ~44 ms cardnet
stall throttling all eight listener threads (8 ÷ 44 ms ≈ 180/s explains the
observed ceiling), and the 62/s baseline and 156/s 3-replica figures
([R30](#r30)) predate [D22](decisions.md#d22) as well. Conc-1 at the 15k demo
scale now measures 195/s — the documented 100k matrix is stale in unknown
directions (the substrate ceiling may finally be real, or may move again).
**Done when:** the three documented 100k configurations (conc-1 baseline,
×3 replicas, conc-8) are re-run green with drain and completion measured per
[R30](#r30)'s two-quantity method; quality.md, README, and the honesty table
quote the new numbers with the old records kept as dated history.
