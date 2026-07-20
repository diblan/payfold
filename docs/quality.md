# Quality grades

Coarse, honest, and **maintained**: any PR touching a module re-grades it in the same
PR ([G6](invariants.md#g6)). The value of this file is currency, not precision.

Last full re-grade: **2026-07-18** (harness adoption).

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
| `billing-engine/renewal-producer` | **C** | `SKIP LOCKED` page claims and the advisory-lock cron guard are in and proven by a two-publisher concurrency test. The single-transaction scan, `System.out` logging, and synchronous trigger endpoint remain | [R9](roadmap.md#r9), [R10](roadmap.md#r10), [R11](roadmap.md#r11) |
| `payment-service/renewal-consumer` | **B** | Bounded failure handling is real and proven by the retry cap, DLQ routing, poison integration test, and `verify.sh` probe; idempotency remains held across midnight. Remaining gaps are low-risk and tracked: the fake PSP is documented scope ([D5](decisions.md#d5)), and logging still uses `System.out` | [R8](roadmap.md#r8), [R9](roadmap.md#r9) |
| `db-migrations` | **B** | Clean, ordered, sole schema authority; V1 carries aspirational tables (`bank_tx`, `recon_match`, `ledger_entry`) no code uses — harmless but reviewer-confusing | — |
| `seed-data-gen` | **C** | Seeds 15k due-today customers idempotently; `SubscriptionSeeder.java` is dead code, seed size hardcoded, `.bat`/`.sh` drift | [R12](roadmap.md#r12) |
| `docker-compose.yaml` + config | **B** | Stack ordering and healthchecks pass; app-specific env names use relaxed binding, yaml contains only consumed keys, and declared named-volume defaults preserve path overrides | — |
| `docs/` + harness | **B** | CI uses pinned Maven wrappers and runs real-container integration tests for both services; `verify.sh` covers the happy path + same-day idempotency + poison probe; the provider-failure probe arrives with R8 | [R8](roadmap.md#r8) |

## Test coverage

Both services have JUnit 5 integration coverage backed by Testcontainers and the real
V1–V4 migrations. The producer has a context smoke test and a confirm-gating job test
against a real-PostgreSQL container with publisher futures faked; the latter proves
unconfirmed rows stay unpublished and are re-picked. `CompetingPublishersTest` proves
two simultaneous publishers claim disjoint pages, publish each row exactly once, and
leave none skipped. The consumer suite publishes real
`renewal.requested` messages through RabbitMQ, covers cross-midnight redelivery
idempotency, and covers the poison path: malformed and contract-violating messages
dead-letter while a subsequent good message processes. CI runs both suites with each
module's pinned Maven wrapper.

`scripts/verify.sh` remains the end-to-end black-box check for the full Compose stack,
including the happy path, same-day idempotency, and a strict poison-message DLQ probe.
Provider-failure coverage remains tracked by [R8](roadmap.md#r8).
