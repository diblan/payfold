# Decision log

One entry per architectural decision, newest last. Keep each entry under one screen.
D1–D6 are reverse-engineered from the code as it stood at harness adoption — recorded
honestly, including known defects, so reviewers see we know where the bodies are.

Format: `D-n — title — date — status (active | superseded by D-m)`

---

## D1 — Transactional outbox over direct publish — pre-2026-07 — active
<a id="d1"></a>
Renewal intent is written to `renewal_outbox` in the same transaction that reads the
subscription data; a separate step drains the outbox to RabbitMQ.
**Why:** publishing directly from the scan would lose atomicity — a crash between DB
commit and publish silently drops renewals (or duplicates them, order-dependent). The
outbox makes "decided to bill" durable before any network I/O.
**Trade-off:** at-least-once delivery; duplicates are possible and must be absorbed
downstream (see D2, [G2](invariants.md#g2)).

## D2 — Idempotency via DB unique constraints, not a dedupe cache — pre-2026-07 — active
<a id="d2"></a>
Duplicate absorption is done by `ON CONFLICT DO NOTHING` against
`uniq_invoice_period`, `uniq_charge_period`, and `payment.idempotency_key` — not by a
processed-message table or an in-memory cache.
**Why:** constraints survive restarts and concurrent consumers with zero extra
infrastructure; the database is already the source of truth.
**Trade-off:** constraint design is load-bearing — a wrong key derivation breaks
idempotency invisibly. [R4](roadmap.md#r4) removed that risk from the consumer by
making the producer-supplied key and period authoritative.

## D3 — Flyway as sole schema authority — pre-2026-07 — active
<a id="d3"></a>
All DDL lives in `db-migrations/`, including the Spring Batch metadata schema (V4);
the producer sets `spring.batch.jdbc.initialize-schema: never`.
**Why:** one migration history, reproducible from scratch, no service races Flyway at
boot. See [G3](invariants.md#g3).

## D4 — Direct exchange + DLX/DLQ topology, consumer-declared — pre-2026-07 — active
<a id="d4"></a>
`billing.renewals` (direct) routes `renewal.requested` to `billing.renewals.main`;
a DLX (`billing.renewals.dlx`) and DLQ (`billing.renewals.dlq`, bound with rk `dlq`)
exist for failures. The consumer declares the full topology; the producer only the exchange.
The known defects were fixed by [R5](roadmap.md#r5) on 2026-07-20: the main queue now
sets `x-dead-letter-routing-key: dlq`, and listener failures have a bounded retry cap.
RabbitMQ queue arguments are immutable, so brokers carrying the pre-R5 queue must
delete it or wipe the RabbitMQ volume before the consumer can redeclare it. The queue
kept its name: a v2 name would leave the old queue bound to `renewal.requested` on
stale brokers, silently duplicating every message into it forever; the same-name
argument change instead makes the strict poison probe fail loudly, preserving
[G5](invariants.md#g5).

## D5 — Fake PSP first — pre-2026-07 — active
<a id="d5"></a>
The initial fake-PSP phase unconditionally called `markPaymentSucceeded` and lasted
until R8 (2026-07-20), which introduced a WireMock mock called over HTTP with a
deterministic subscription-id failure rule.
**Why:** the project's point is delivery semantics (outbox, idempotency, failure
routing) — provider integration is orthogonal and would have front-loaded complexity.
**Current state:** provider outcomes are business failures and are never dead-lettered;
failed payments are terminal, with dunning still a non-goal. A "payment" still moves
no real money, so the real-PSP boundary in the [non-goals table](roadmap.md#non-goals)
is unchanged.

## D6 — Spring Batch tasklets over a custom loop — pre-2026-07 — active
<a id="d6"></a>
The renewal job is a two-step Spring Batch job rather than a hand-rolled scheduler loop.
**Why:** job-instance identity on `scheduleDate` gives same-day re-run protection for
free, plus execution metadata and restartability.
**Trade-off:** job-instance identity only guards a single node ([R7](roadmap.md#r7)
added the cross-instance guard); the default `JobLauncher` is synchronous, which
blocked the trigger endpoint until [R10](roadmap.md#r10) gave the endpoint its own
async launcher ([D9](#d9)).

## D7 — Harness adoption — 2026-07-18 — active
<a id="d7"></a>
The repo adopts harness engineering (per OpenAI's *harness engineering* post):
`AGENTS.md` as the agent map, `docs/` as versioned system knowledge
(architecture / invariants / decisions / quality / roadmap), `scripts/verify.sh` as the
machine-checkable definition of "working", and a session protocol of **one roadmap item
per session** with no drive-by fixes.
**Why:** the project is built primarily by AI agents; without explicit scope guardrails
and a verification ratchet, ballooning scope and doc rot are the default outcome.
**Consequence:** code gaps found during adoption were *not* fixed — they became
[roadmap items](roadmap.md) with acceptance criteria instead.

## D8 — Message contract v1 — 2026-07-20 — active
<a id="d8"></a>
`renewal.requested` v1 carries identity (`event_id`, equal to the outbox row id), the
stable `idempotency_key` (`sub-<subscription_id>|<due_date>`), `due_date`, the full
billing period, `occurred_at`, and `schema_version: 1`, alongside the original billing
fields. The consumer trusts payload values only and rejects missing or invalid billing
material through `InvalidRenewalMessageException`; it never guesses a key or date.
**Why:** idempotency material must be minted once by the single writer that owns the
truth: the producer's scan transaction. Consumer-side derivation introduces a second
clock and makes redelivery timing observable in billing identity.
**Trade-off:** the payload is fatter, and producer and consumer must agree on the
documented contract. Versioning follows [G8](invariants.md#g8): v1 evolves additively;
removal or re-typing requires a version bump and another decision entry.

## D9 — Dual job launchers: async endpoint, sync scheduler — 2026-07-21 — active
<a id="d9"></a>
[R10](roadmap.md#r10) gives the actuator endpoint a dedicated
`TaskExecutorJobLauncher` (`asyncJobLauncher`) on a single-thread executor:
`POST /actuator/renewal-job` returns the `executionId` immediately and
`GET /actuator/renewal-job/{executionId}` reports status from the `JobExplorer`.
The cron scheduler keeps Spring Batch's default synchronous `jobLauncher`.
**Why:** the scheduler releases its Postgres advisory lock when `run()` returns
([R7](roadmap.md#r7)); an async launch there would release the lock while the job
still runs and silently forfeit cross-instance serialization. Asynchrony is a
property of the trigger endpoint, not of the job.
**Trade-off:** two launcher beans make every unqualified `JobLauncher` injection
ambiguous, so all sites qualify explicitly. The executor bean must not be named
`taskExecutor` — `@EnableBatchProcessing` wires a bean of that name into the
default launcher, which would make the cron path async too. The single launcher
thread serializes concurrent force-triggers instead of stacking job threads.

## D10 — Keyset scan over all actives, due filter in-page — 2026-07-21 — active
<a id="d10"></a>
[R11](roadmap.md#r11) replaced the scan's single `INSERT...SELECT` transaction with a
keyset-paginated tasklet loop: one transaction per page of `app.scanPageSize` active
subscriptions claimed by primary-key order, the due-window filter applied inside the
page, and the cursor persisted in the step ExecutionContext atomically with the
page's inserts.
**Why keyset-over-all-actives:** the nightly scan reads every active subscription
(10M at target scale) to find the due ~330k rather than indexing the due predicate:
`due_ts` is `renewed_at + plan.interval` localized to a configurable timezone — a
cross-table, timezone-dependent (hence non-IMMUTABLE) expression Postgres cannot put
in an expression index — and a PK-ordered pass is sequential-friendly I/O that adds
no write amplification to the hot subscription table.
**Trade-off:** scan cost scales with the active base, not the due set. That makes the
in-page filter contract load-bearing: the page query must report its row count and
last id regardless of how many rows were due, so all-not-due pages advance the cursor
and the loop ends on a short page, never on `inserted == 0`.
**Publish side:** measured before deciding (15k baseline, 2026-07-21: publishStep
3.0 s ≈ 5k msg/s single-channel, sends already pipelined within a page behind one
batched confirm await). At that rate the nightly 330k publishes in ~66 s, so no new
mechanism is justified; R11 only raises `app.publishPageSize` 1000 → 10000 to cut
per-page claim/commit/confirm-await overhead ~10× at 1M scale. A
`BatchingRabbitTemplate` was explicitly rejected: it changes the wire format and
would break the v1 contract ([G8](invariants.md#g8)).

## D11 — Deploy epilogue: publish versioned multi-arch images to GHCR — 2026-07-25 — active
<a id="d11"></a>
The external platform repo (k3s + Flux GitOps, managing the portfolio cluster) is
ready to onboard payfold, and a GitOps cluster may only pull artifacts from Git or a
registry — never from a dev-machine path or a local compose build. That crosses the
"Kubernetes / cloud deploy" non-goal boundary, so this entry sanctions exactly one
epilogue, [R18](roadmap.md#r18): a manually pushed `vX.Y.Z` git tag publishes four
images to `ghcr.io/diblan/` — the two services, plus `payfold-migrations` (Flyway
with `db-migrations/*.sql` baked in: compose's host bind mount has no Kubernetes
equivalent) and `payfold-seed-data-gen` (same host-mount problem; the platform's
demo runs require the seed story), both runnable as run-to-completion Jobs.
Tags are immutable semver, never `latest` or any mutable tag — the platform pins
exact tags in Git and orders upgrades by semver. Every tag is a linux/amd64 +
linux/arm64 manifest list (buildx): the target cluster schedules on an arm64 Pi
node that cannot run amd64-only images. Compose's flyway and seed-data services
switch to building the same images, so `verify.sh` exercises the artifact shape the
cluster will run instead of letting demo stack and deploy artifact drift apart.
**What stays out (the non-goal otherwise holds):** no k8s manifests, no helm chart,
no orchestration in this repo. The coupling surface the platform consumes is
exactly: the published images, the
[config truth table](architecture.md#configuration-truth-table), ports 8080/8081,
and `/actuator/health` — any change to that surface is a loud, flagged event, never
an incidental edit.

## D12 — Ops visualization via Grafana, not a custom UI — 2026-07-26 — active
<a id="d12"></a>
Carves one exception out of the "Any UI" non-goal for [R21](roadmap.md#r21):
payfold gets dashboards, but as provisioned industry ops tooling — Prometheus +
Grafana containers in compose, datasources and dashboards checked in as code —
never a hand-built webpage.
**Why:** the system's story is operational (publish rates, outcome counts, queue
and DLQ depths, drain speed), and [R9](roadmap.md#r9)'s metric names are already a
documented contract, so dashboards consume an existing surface; using the tool the
industry actually runs is worth more — to interviewers and to the user's own
experience — than any custom page. The external platform repo independently
reached the same conclusion (kube-prometheus-stack in-cluster, plus a payfold
autoscaling dashboard since its P9); payfold's own dashboards cover the pipeline
internals and must work standalone on compose, with panel queries reusable by the
platform.
**Boundary:** no custom frontend, no customer-facing pages. Grafana and Prometheus
are infrastructure containers like WireMock — the "More services" non-goal is
about business services and stays intact.

## D13 — SEPA mock-bank: async settlement replaces the happy flow — 2026-07-26 — active
<a id="d13"></a>
Promotes two non-goal boundaries for [R23](roadmap.md#r23): a third (business)
service, and a deliberate break of the instant-settlement fiction. Today a
"payment" settles synchronously inside the PSP call — unrealistic for SEPA direct
debits (domiciliëringen), where a collection is submitted and its outcome
(settled, failed, charged back) arrives asynchronously, later, from the bank.
R23 introduces a mock-bank service that accepts submissions and calls back over a
webhook with configurable chaos — delay distributions, failure rates, chargebacks
— potentially as multiple banks (Netflix-style per-country creditor accounts, each
webhooking back). Confirmed by the user twice (2026-07-19 and 2026-07-26) as the
differentiator: it turns the pipeline from a happy-flow demo into a system that
absorbs asynchronous, adversarial reality, and forces the code to scale beyond a
single bank.
**Consequences:** `payment` grows a submitted→terminal state machine; message and
payload changes follow [G8](invariants.md#g8) (additive within v1, else v2 + a new
decision entry); schema changes arrive via new migrations ([G3](invariants.md#g3));
the item is an epic and is expected to split into sub-items at execution, each
with its own acceptance criteria.
**Boundary unchanged:** still no real PSP, no real bank, no real money movement.

## D14 — Publish-page channel budget via an in-flight window, not channelCheckoutTimeout — 2026-07-26 — active
<a id="d14"></a>
[R25](roadmap.md#r25): with correlated confirms, `CachingConnectionFactory` parks any
channel whose confirms are pending (`returnToCache` → `channelsAwaitingAcks`) instead
of re-caching it, so each unconfirmed send holds one channel — publishStep's
unbounded pipelined page therefore opened a new channel per send while the broker
lagged, up to RabbitMQ's `channelMax` (2047). Observed at cold boot under image-pull
load (R21 session): zero confirms, `AmqpResourceNotAvailableException`, job failed on
the zero-progress rule; [G1](invariants.md#g1) re-picked every row safely, but a
degraded broker should not cost a job failure.
**Decision:** bound in-flight sends per page with a
`Semaphore(app.publishInFlightLimit)` (default 100) whose permits release on
confirm-future completion — spring-rabbit completes that future synchronously with
ack delivery, so the window tracks the broker exactly.
`spring.rabbitmq.cache.channel.size` is kept equal to the limit so a parked channel
re-enters the cache on confirm and is reused: the page publishes within a fixed
channel budget, with no open/close churn. A window stalled to the page's
`app.confirmTimeoutMs` deadline stops sending; unsent rows stay unpublished for
re-pick; the loud zero-progress failure is unchanged.
**Why not `channelCheckoutTimeout`:** it does turn the channel cache into a bounded
pool, but checkout permits release only when a channel physically re-caches, so with
every permit parked behind pending confirms each further send blocks *inside*
`convertAndSend` — a trickling broker stretches the page unboundedly past the
confirm deadline (the 03:00 cron would hang rather than fail), and a dead broker
throws `AmqpTimeoutException` mid-send-loop, abandoning already-confirmed rows
unmarked (they would re-publish as duplicates; harmless under
[G2](invariants.md#g2), but needless). The app-level window integrates with the
existing page deadline and keeps the failure semantics identical.
**Trade-off:** peak publish throughput is capped at window ÷ confirm round-trip; 100
in flight covers the measured 5k msg/s page baseline ([D10](decisions.md#d10)) with
margin. Up to 100 idle channels stay cached on a quiet connection — well under
`channelMax` and cheap on the broker.

## D15 — R23 execution design: settlement inbox behind the queue, bank-agnostic contract, FastAPI mock bank — 2026-07-26 — active, flow scope amended by [D17](#d17)
<a id="d15"></a>
Execution-level decisions for [D13](decisions.md#d13)'s epic, agreed in the
2026-07-26 design discussion; where this differs from the overnight design brief
(gitignored `notes/`), this entry wins. The split itself lives in
[R23a–R23e](roadmap.md#r23).
**Flow:** processing a renewal ends by *submitting* an SDD collection to a mock
bank — `payment.status = 'submitted'`, message ACKed, nothing finalized. The bank
applies its chaos profile and reports the outcome later via a signed webhook
(HMAC-SHA256, per-bank shared secret). Renewals **become** direct-debit
collections: the mock PSP was always a stand-in for the bank and is superseded
within the epic, not run alongside ([D5](decisions.md#d5)'s fake-counterparty
role passes to the bank; its deterministic-outcome trick is inherited, see below).
**Receiver placement:** the webhook endpoint lives on payment-service, not a new
gateway service — [D13](decisions.md#d13) grants exactly one new service (the
bank), and the receiver's correctness depends on writing tables payment-service
owns in one transaction. Service boundaries follow data ownership, not transport.
**Inbox pattern with a publish relay:** the receiver's transaction inserts the
raw notification into `settlement_inbox` (unique on bank id + notification id;
duplicate insert → no-op, still 200) and *only then* acks — after a 200 the bank
never resends, so the durable row is what makes the 200 truthful. A relay
publishes unpublished inbox rows to a settlements queue (the row doubles as its
own outbox via `published_at` — the [D1](decisions.md#d1) dual-write answer,
mirrored inbound), and a settlement listener finalizes: the outbox on the way
out, an inbox on the way in. **Why the queue hop** instead of finalizing inside
the webhook transaction: it routes settlements through the pipeline's existing
[G2](invariants.md#g2)/[G5](invariants.md#g5) machinery — idempotent redelivery
by constraint, poison to a bounded DLQ instead of an endless bank-retry loop —
and settlement processing inherits the queue-depth scaling story (a slow bank's
backlog drains like any other backlog).
**Bank-agnostic contract — accepted simplification:** the receiver normalizes
every bank's callback into one versioned internal settlement message
([G8](invariants.md#g8) discipline, new contract at v1); the source bank is a
field, which is what makes bank N+1 a compose entry. Voiced deliberately: in the
real industry a pure bank-agnostic edge often *doesn't* survive, because each
bank relationship carries its own surface — mTLS certs, IP allowlisting,
protocol quirks, rate limits — and at scale that ownership becomes a dedicated
bank-gateway service. We knowingly accept that simplification; the normalization
seam is exactly where such a gateway would split off if ever promoted (which
would need its own decision entry).
**Mock bank stack:** Python 3 + FastAPI, in-repo (`mock-bank/`), one generic
image configured per instance via env — deliberately polyglot, and a fit:
delayed outbound callbacks are natural in asyncio, where WireMock can neither
hold per-collection state nor schedule outbound calls. Outcomes are **derived
deterministically from the debtor IBAN** ([R8](roadmap.md#r8)'s recomputable-rule
precedent): the seeder controls the outcome mix in aggregate, verify.sh predicts
it exactly per row; only *delays* are bank-profile config (fast for verify,
visible for demo).
**SEPA realism boundary:** a simplified SDD-Core-flavored model — real ISO 20022
reason codes (AC04 closed account, AM04 insufficient funds, MD01 no mandate,
MD06 payer objection = our chargeback), pain.008/pain.002/camt.054 vocabulary in
docs, rulebook timelines scaled from days to configurable seconds. Webhook
delivery itself is a PSP-style fiction (GoCardless-shaped): real banks report
via file channels (EBICS) — stated honestly in the docs. Sources and the
real→Payfold mapping live in gitignored `notes/sepa-references.md`.
**Dunning boundary:** a chargeback is a recorded fact (terminal state + reason);
the subscription stays advanced and nothing compensates — reacting is dunning,
promoted separately with a gate in [D16](decisions.md#d16).
**Amended same day by [D17](#d17):** the PSP-supersession scope. The user
clarified both payment methods are wanted; cards migrate onto the same
settlement spine in [R23f](roadmap.md#r23f) instead of retiring with
[R23b](roadmap.md#r23b). Everything else in this entry stands.

## D16 — Dunning: promoted from non-goal to a gated future epic — 2026-07-26 — active
<a id="d16"></a>
Promotes the "no dunning" non-goal into [R26](roadmap.md#r26), **blocked on
[R23](roadmap.md#r23)**. User-confirmed 2026-07-26: dunning is realistic,
industry-precise vocabulary, and demonstrates domain knowledge — a chargeback or
failed collection is where a billing system's real work starts. The gate exists
because R23 *produces* the inputs dunning consumes (terminal `failed` /
`charged_back` states with ISO reason codes); building it earlier would invent
its own triggers. R23's only obligation to dunning: record terminal outcomes
richly enough — state, reason code, timestamps — that R26 needs no schema rework
of R23's tables ([G3](invariants.md#g3) makes retrofits expensive).
**Promotion-level scope for R26** (split at execution, like R23): per-reason
retry policy (AM04 insufficient funds is retriable; AC04 closed account and MD01
no mandate are not), a `past_due` grace lifecycle on the subscription, bounded
attempts ending in cancellation. No new service; no notification channels
(email etc. stay out).
**Boundary unchanged:** proration, refunds-as-a-flow, and tax remain non-goals.

## D17 — Two payment methods, one event-driven settlement spine — 2026-07-26 — active
<a id="d17"></a>
Amends [D15](#d15)'s flow scope after a user clarification the same day. The
2026-07-19 critique ("something that instantly says there's a correct bank
transfer is unrealistic") targeted the **synchronous request-response shape** —
tell an API "do this payment", block, receive "it happened" — not direct debits
specifically. *Async* here means what the rest of this system means by it:
distributed, event/message-driven confirmation. And the product vision is
Netflix-like: the customer chooses a payment method, and the project is built to
grow from one method to two — so cards are not superseded, they are the second
traveler on the same spine.
**Decision:** customers carry a `payment_method` (`card` | `sdd`), seeded with an
env-tunable mix.
- **SDD** goes async first, exactly per [D15](#d15) ([R23a](roadmap.md#r23a)–[R23e](roadmap.md#r23e) unchanged).
- **Card stays on the existing synchronous WireMock PSP through the epic's
  middle** — [R8](roadmap.md#r8)'s verify assertions survive verbatim
  ([G7](invariants.md#g7) stays clean, no reshape controversy) and the pipeline
  never has a window where nothing settles.
- **[R23f](roadmap.md#r23f) then migrates cards onto the async spine,** modeling
  cards honestly: authorization **is** a genuine synchronous round trip in the
  real card network (merchant → PSP → scheme → issuer, seconds), so submission
  returns a sync auth verdict and a decline is terminal immediately; but
  fulfillment-grade confirmation (capture/settlement) arrives later as an event
  — the industry's own fulfill-via-webhook rule — through the same
  inbox → queue → listener machinery, on a fast profile with card decline
  vocabulary. The counterparty is the **same mock-counterparty image** in a
  `card` scheme; WireMock retires there, and [R19](roadmap.md#r19)'s
  platform-facing PSP contract flags move there with it.
**Why this shape:** the difference between cards and SDD is not sync vs async —
every payment method is submit-now-confirm-later once you look past the auth hop
— it is the **latency and trustworthiness of the first signal** (cards: a
reliable verdict in seconds; SDD: nothing trustworthy for days). One state
machine with per-scheme profiles models that truthfully, and the card scheme
becomes the first proof that [D15](#d15)'s bank-agnostic contract is genuinely
counterparty-agnostic.
**Noted, not scoped:** the user's phone-bill analogy (pay manually vs
automatically) points at a third method — customer-initiated push payment with
open-invoice reconciliation, which is exactly what the dormant
`bank_tx`/`recon_match` tables await. Stays a non-goal until its own decision
entry.

## D18 — R28 recovery design: pull-shaped reconciliation sweeper, delivery loss as a deterministic rule — 2026-08-01 — active
<a id="d18"></a>
Design outcome of the R28 read (2026-07-31 session).

**The gap, precisely:** a counterparty's webhook delivery is bounded-loud by
design (R23a) and its entire state — collection records AND pending deliveries —
is one in-memory dict per instance (`app.state.records`, single-worker,
verified in source). So the outcome can strand two ways: (1) retry exhaustion
(give-up counted, record retained), and (2) container recreate (everything
gone; `GET /collections/{id}` → 404). Widening the retry envelope (the R23f
mitigation) only stretches the fuse on (1) and does nothing for (2).

**Decision shape — pull, not push-harder:** real SEPA reporting is pull-shaped
(EBICS-fetched pain.002/camt.054) precisely so a missed push cannot strand
state. R28 adopts that: a consumer-side recovery sweeper (the R7
advisory-lock + page-scan pattern, same shape R26's dunning sweeper will use,
deliberately separate task) scans payments stuck `submitted` older than a
scaled threshold (`RECOVERY_STALE_AFTER_SECONDS`, default > the worst-case
delivery envelope + settlement delay + chargeback lag) and re-queries the
owning counterparty per the registry:

- `GET /collections/{id}` → 200 with a terminal classification: the sweeper
  synthesizes the missing settlement INTO `settlement_inbox` (bank id + the
  bank's stored notification id), and the existing relay → queue → listener
  spine finalizes it. The inbox unique constraint dedupes against a webhook
  that did arrive late — recovery and delivery can race safely (G2 by
  construction, no new idempotency machinery).
- `GET /collections/{id}` → 404 (restart amnesia): the sweeper RESUBMITS the
  collection with the same collection id. Outcomes are deterministic from
  IBAN/token, so resubmission reproduces the same classification and fresh
  notifications — the bank's amnesia is harmless by design. (This is why R8's
  deterministic-outcome trick keeps paying: recovery needs no stored truth at
  the counterparty.)
- No counterparty resend endpoint. It would add surface and still die to (2).

**Deterministic verify.sh modeling — delivery loss as a bank rule:** a new
IBAN/token suffix (e.g. `95`... note: 95 is earmarked for R26b's
retry-then-settle rule — use `94`) classifies normally but NEVER schedules
notifications ("silent bank"). The 94-cohort can only complete through the
sweeper, so verify.sh asserts: zero stuck `submitted` including the 94-cohort,
every 94-payment terminal with an inbox row, and `settlements_recovered_total`
exactly equal to the 94-cohort size. No timing games, no outage choreography —
the R6 "design races out" rule applied to recovery. The chaos demo gets the
LIVE version: kill cardnet mid-drain (`--no-deps` recreate), watch the stranded
cohort recover on the sweeper tick.

**Observability:** `recovery_sweeps_total{result=recovered|resubmitted|noop}` +
log per recovered collection; dashboard panel on the settlement row.

**Scope boundaries:** dropped CHARGEBACK notifications (payment already
`succeeded`, so staleness-by-`submitted` never re-checks it) are explicitly OUT
— that is full statement reconciliation, i.e. the dormant
`bank_tx`/`recon_match` tables' future promotion, and gets its own decision
entry when it comes. R26 boundary per the split draft: the recovery sweeper
reacts to MISSING signals, dunning to RECEIVED ones; they share the scheduled
task pattern, never a trigger.

**Cost estimate:** mock-bank +1 rule (tag bump rides R28), one consumer scheduled
task + config rows, verify.sh tightening, no schema change (inbox rows are the
write path; a `settlements_recovered_total` counter carries provenance).
