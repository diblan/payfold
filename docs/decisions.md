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
