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
| `billing-engine/renewal-producer` | **C** | Tested (smoke, confirm-gating, competing-publisher, and async-trigger suites), observable (eager counters + built-in batch timers), documented; the single-transaction scan remains the tracked scale gap | [R11](roadmap.md#r11), [R14](roadmap.md#r14) |
| `payment-service/renewal-consumer` | **A** | Tested (real-broker integration suite including decline, timeout, and poison paths), observable (SLF4J, `renewals_processed_total{outcome}`, Prometheus endpoint, and listener timer), and documented (contract + architecture); no known behavior defects | [R14](roadmap.md#r14) |
| `db-migrations` | **B** | Clean, ordered, sole schema authority; V1 carries aspirational tables (`bank_tx`, `recon_match`, `ledger_entry`) no code uses — harmless but reviewer-confusing | — |
| `seed-data-gen` | **C** | Seeds 15k due-today customers idempotently; `SubscriptionSeeder.java` is dead code, seed size hardcoded, `.bat`/`.sh` drift | [R12](roadmap.md#r12) |
| `mock-psp/` (WireMock) | **B** | Deterministic decline rule (last-hex-char class) rendered from inert `.json.tpl` templates by the compose entrypoint; healthchecked; exercised end-to-end by the consumer integration suite and `verify.sh`'s exact per-row assertions. The sed-render entrypoint itself has no direct test | — |
| `docker-compose.yaml` + config | **B** | Stack ordering and healthchecks pass; app-specific env names use relaxed binding, yaml contains only consumed keys, and declared named-volume defaults preserve path overrides | — |
| `docs/` + harness | **B** | CI uses pinned Maven wrappers and runs real-container integration tests for both services; `verify.sh` covers the happy path via the async trigger (<1s POST assert + execution-status polling), same-day idempotency, poison probe, per-row predicted PSP outcomes with an exact failed count, and same-run metric/DB delta cross-checks | — |

## Test coverage

Both services have JUnit 5 integration coverage backed by Testcontainers and the real
V1–V4 migrations. The producer has a context smoke test and a confirm-gating job test
against a real-PostgreSQL container with publisher futures faked; the latter proves
unconfirmed rows stay unpublished and are re-picked. `CompetingPublishersTest` proves
two simultaneous publishers claim disjoint pages, publish each row exactly once, and
leave none skipped.
`AsyncTriggerEndpointTest` proves the endpoint trigger returns an execution id
while the job is still gated mid-publish, and that the endpoint's read operation
tracks the execution to COMPLETED. The consumer suite publishes real
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
