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
| `billing-engine/renewal-producer` | **C** | Outbox scan/publish and a real-Postgres context smoke test work, its config is truthful, and same-day retries are idempotent; no publisher confirms or `SKIP LOCKED`, the single-tx scan won't survive 10M rows, and progress uses `System.out` | [R6](roadmap.md#r6), [R7](roadmap.md#r7), [R9](roadmap.md#r9), [R10](roadmap.md#r10), [R11](roadmap.md#r11) |
| `payment-service/renewal-consumer` | **D** | A real-Postgres/RabbitMQ integration test covers listener-to-payment wiring, config is truthful, and actuator health passes. Still D per rubric: the DLQ is unreachable and cross-midnight redelivery duplicates records | [R4](roadmap.md#r4), [R5](roadmap.md#r5), [R8](roadmap.md#r8) |
| `db-migrations` | **B** | Clean, ordered, sole schema authority; V1 carries aspirational tables (`bank_tx`, `recon_match`, `ledger_entry`) no code uses — harmless but reviewer-confusing | — |
| `seed-data-gen` | **C** | Seeds 15k due-today customers idempotently; `SubscriptionSeeder.java` is dead code, seed size hardcoded, `.bat`/`.sh` drift | [R12](roadmap.md#r12) |
| `docker-compose.yaml` + config | **B** | Stack ordering and healthchecks pass; app-specific env names use relaxed binding, yaml contains only consumed keys, and declared named-volume defaults preserve path overrides | — |
| `docs/` + harness | **B** | CI uses pinned Maven wrappers and runs real-container integration tests for both services; verify.sh covers the happy path + same-day idempotency only — poison/failure probes arrive with R5/R8 | [R5](roadmap.md#r5), [R8](roadmap.md#r8) |

## Test coverage

Both services have JUnit 5 integration coverage backed by Testcontainers and the real
V1–V4 migrations. The producer test loads the Spring context against PostgreSQL and
asserts the `renewalJob` bean exists. The consumer test publishes a real
`renewal.requested` message through RabbitMQ and awaits the resulting succeeded payment
in PostgreSQL. CI runs both suites with each module's pinned Maven wrapper.

`scripts/verify.sh` remains the end-to-end black-box check for the full Compose stack,
including the happy path and same-day idempotency. Poison-message, provider-failure,
and cross-midnight idempotency coverage remain tracked by [R5](roadmap.md#r5),
[R8](roadmap.md#r8), and [R4](roadmap.md#r4), respectively.
