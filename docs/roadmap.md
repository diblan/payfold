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
| Proration, refunds, tax | Each is a project of its own; the renewal happy path + failure path is the thesis. Dunning left this row 2026-07-26: [D16](decisions.md#d16) promotes it to [R26](#r26), gated on [R23](#r23) |
| Event-sourcing rewrite | The outbox pattern *is* the demonstration; rewriting the persistence model restarts the project |
| More services | Two services already demonstrate cross-service delivery semantics; a third must earn its place via a decision entry — [D13](decisions.md#d13) grants exactly one: the mock-bank settlement service ([R23](#r23)) |
| Reconciliation / ledger flows | `bank_tx`, `recon_match`, `ledger_entry` stay dormant until promoted |

## Items

Ordering principle: *repair the feedback loop → correctness → resilience → scale → story*.
Dependencies: R1, R2 → R3 → R4–R8; R4 → R5; R10 → R11, R12.
Story phase (2026-07-26): R20 → R22; R21 → R22; R22 → R24 ([R17](#r17) pairs
naturally with [R20](#r20)'s scale runs; [R23](#r23) re-triggers [R24](#r24)).
SEPA phase (2026-07-26): [R23](#r23) split per [D15](decisions.md#d15)/[D17](decisions.md#d17)
into R23a → R23b → R23c → R23d → R23e → R23f, strictly in order;
R23 → R26 ([D16](decisions.md#d16)). Between [R23b](#r23b) and [R23c](#r23c) the
SDD cohort parks in `submitted` while cards settle as today ([D17](decisions.md#d17))
— a tag proposed from that window must say so, and the seeded SDD share should
stay low until the loop closes.

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

*Last run: 2026-07-26 (after R20, commit c390bc6 — 3rd run).*

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
### [ ] R23 — SEPA mock-bank: async settlement ([D13](decisions.md#d13), design [D15](decisions.md#d15)/[D17](decisions.md#d17)) *(epic — split 2026-07-26 into R23a–R23f below; check when all six are checked)*
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
### [ ] R23f — Cards join the async spine; WireMock retires ([D17](decisions.md#d17))
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

<a id="r26"></a>
### [ ] R26 — Dunning: failed collections get a lifecycle ([D16](decisions.md#d16)) *(epic — blocked on [R23](#r23); split at execution)*
**Scope (promotion-level):** consumes [R23](#r23)'s terminal outcomes — no new
service, no notification channels. Per-reason retry policy (AM04 insufficient
funds retriable on a schedule; AC04 closed account and MD01 no mandate are not),
a `past_due` grace lifecycle on the subscription, bounded attempts ending in
cancellation; schema via new migrations ([G3](invariants.md#g3)).
**Done when (epic-level):** a retriable failed collection demonstrably
re-collects on schedule and settles or exhausts into cancellation; a chargeback
moves the subscription through the grace lifecycle instead of being a dead-end
fact; verify.sh models the retry outcomes deterministically; detailed sub-item
acceptance criteria are written when the epic is split.
