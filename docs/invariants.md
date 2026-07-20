# Invariants (golden rules)

These are the load-bearing rules of the system. They are numbered and **never
renumbered**; retiring or changing one requires an entry in [decisions.md](decisions.md).
Each rule states its enforcement mechanism and current status. A `VIOLATED` status is
not shameful — it is a tracked debt with a roadmap item; an *undocumented* violation is.

<a id="g1"></a>
## G1 — Every renewal message goes through the outbox

A renewal event reaches RabbitMQ only via a `renewal_outbox` row written in the same
database as the business data it derives from. No code path may call
`RabbitTemplate.convertAndSend` for renewals outside `OutboxPublisher` draining the outbox.

*Why:* atomicity between "we decided to bill this subscription" and "we will tell the
payment service" — the transactional outbox pattern is the core of this project.
*Enforced by:* code review; only `OutboxPublisher` touches the template.
*Status:* **HELD**

<a id="g2"></a>
## G2 — The consumer is idempotent under redelivery at ANY time

Processing the same message twice — including across a midnight boundary, a deploy, or
a week later — must produce exactly one invoice, charge, and payment. Idempotency keys
and billing periods derive **only from message content**, never from wall-clock time at
consume time.

*Why:* RabbitMQ is at-least-once; redelivery timing is not under our control.
*Enforced by:* DB unique constraints (`uniq_invoice_period`, `uniq_charge_period`,
`payment.idempotency_key`) + producer-supplied key/period material + a cross-midnight
redelivery integration test; the consumer never reads the clock.
*Status:* **HELD** (since R4, 2026-07-20)

<a id="g3"></a>
## G3 — Schema changes only via Flyway

Every schema change is a new `db-migrations/V*.sql`. Applied migrations are immutable.
No service may create or alter tables at runtime (Spring Batch metadata included — V4).

*Enforced by:* `spring.batch.jdbc.initialize-schema: never`; review.
*Status:* **HELD**

<a id="g4"></a>
## G4 — Money is integer cents, and always travels with its currency

No floats, no implicit currency. Every amount column and payload field is
`*_cents BIGINT` paired with a `currency` code.

*Enforced by:* schema (V1); review.
*Status:* **HELD**

<a id="g5"></a>
## G5 — Unprocessable messages reach the DLQ in bounded attempts

A message that cannot be processed must land in `billing.renewals.dlq` after a bounded
number of attempts. Never infinite requeue; never silent drop.

*Why:* poison messages must not block the listener forever or disappear outside the
observable failure path.
*Enforced by:* a five-attempt retry cap with bounded exponential backoff; DLX + explicit
`dlq` routing key; `default-requeue-rejected: false`; a no-retry fast path for
deterministic contract violations; a poison-path integration test; and the strict
`verify.sh` poison probe.
*Status:* **HELD** (since R5, 2026-07-20)

<a id="g6"></a>
## G6 — Docs change in the same PR as behavior

A PR that changes message flow, schema, config, or module quality also updates
[architecture.md](architecture.md), [quality.md](quality.md) grades for touched modules,
and checks off / re-orders [roadmap.md](roadmap.md) as needed.

*Why:* stale docs are worse than no docs — agents and reviewers trust them.
*Status:* **HELD** (from adoption, 2026-07-18)

<a id="g7"></a>
## G7 — `scripts/verify.sh` is a ratchet

`scripts/verify.sh` must pass before any change is complete. It may be edited only to
**add or tighten** assertions — never loosened so a change can pass. If a change breaks
verify.sh, the change is wrong (or the tightening belongs in the same PR).

*Status:* **HELD** (from adoption, 2026-07-18)

<a id="g8"></a>
## G8 — The message payload is a versioned contract

The `renewal.requested` payload is a contract between producer and consumer. Within a
version, changes are additive only (consumers tolerate unknown fields); removing or
re-typing a field requires a version bump and a decision entry.

*Status:* **HELD** — contract v1 is documented in
[architecture.md](architecture.md#message-contract--renewalrequested-v1) and fully
populated by the producer.
