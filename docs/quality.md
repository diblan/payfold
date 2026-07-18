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
| `billing-engine/renewal-producer` | **C** | Outbox scan/publish works and is same-day idempotent; but no publisher confirms, no `SKIP LOCKED`, single-tx scan won't survive 10M rows, progress via `System.out` | [R6](roadmap.md#r6), [R7](roadmap.md#r7), [R9](roadmap.md#r9), [R10](roadmap.md#r10), [R11](roadmap.md#r11) |
| `payment-service/renewal-consumer` | **D** | Billing chain works same-day; pom is clean and actuator health passes since R1 (2026-07-18). Still D per rubric: DLQ is an unreachable safety net, PSP config is dead, and cross-midnight redelivery duplicates records | [R2](roadmap.md#r2), [R4](roadmap.md#r4), [R5](roadmap.md#r5), [R8](roadmap.md#r8) |
| `db-migrations` | **B** | Clean, ordered, sole schema authority; V1 carries aspirational tables (`bank_tx`, `recon_match`, `ledger_entry`) no code uses — harmless but reviewer-confusing | — |
| `seed-data-gen` | **C** | Seeds 15k due-today customers idempotently; `SubscriptionSeeder.java` is dead code, seed size hardcoded, `.bat`/`.sh` drift | [R12](roadmap.md#r12) |
| `docker-compose.yaml` + config | **C** | Stack boots and orders dependencies correctly; all healthchecks pass since R1 (2026-07-18). Remaining: dotted env names bind only by accident, dead yaml keys, RabbitMQ volume hardcoded to `/payfold/rabbitmq`, and `.env.example`'s `POSTGRES_VOLUME=pg_data` refers to an undeclared named volume — the documented first-time `docker compose up` fails until the value is changed to a path | [R2](roadmap.md#r2) |
| `docs/` + harness | **B** | Fresh and complete as of adoption; verify.sh covers the happy path + same-day idempotency only — poison/failure probes arrive with [R5](roadmap.md#r5)/[R8](roadmap.md#r8) | [R3](roadmap.md#r3) |

## Test coverage

**Zero automated tests exist** (no `src/test`, no test dependencies, no Maven wrapper).
The only executable check is `scripts/verify.sh` (end-to-end, black-box). Test
infrastructure is [R3](roadmap.md#r3) and is deliberately ordered before every
correctness item, because their acceptance criteria are tests.
