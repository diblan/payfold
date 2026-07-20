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
**Trade-off:** the default synchronous `JobLauncher` makes the trigger endpoint block
for the whole run ([R10](roadmap.md#r10)); job-instance identity only guards a single
node ([R7](roadmap.md#r7)).

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
