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
| Kubernetes / cloud deploy | Compose demonstrates the architecture; orchestration adds ops surface, not distributed-systems insight |
| Real PSP or money movement | Mock PSP ([R8](#r8)) exercises every interesting failure path without credentials or compliance |
| Auth / multi-tenancy | Orthogonal to the billing pipeline story |
| Any UI | The consumers of this system are curl, psql, and the RabbitMQ console |
| Dunning, proration, refunds, tax | Each is a project of its own; the renewal happy path + failure path is the thesis |
| Event-sourcing rewrite | The outbox pattern *is* the demonstration; rewriting the persistence model restarts the project |
| More services | Two services already demonstrate cross-service delivery semantics; a third must earn its place via a decision entry |
| Reconciliation / ledger flows | `bank_tx`, `recon_match`, `ledger_entry` stay dormant until promoted |

## Items

Ordering principle: *repair the feedback loop → correctness → resilience → scale → story*.
Dependencies: R1, R2 → R3 → R4–R8; R4 → R5; R10 → R11, R12.

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
### [ ] R6 — Publisher confirms
**Scope:** `OutboxPublisher`, `RabbitConfig`, publishStep.
Correlated publisher confirms; a row's `published_at` is set only after broker confirm;
unconfirmed rows are naturally re-picked by the next page.
**Done when:** a test proves unconfirmed rows are retried; the at-least-once window is
documented in [invariants.md](invariants.md) under [G1](invariants.md#g1)/[G8](invariants.md#g8).

<a id="r7"></a>
### [ ] R7 — Competing-producer safety
**Scope:** publishStep SQL + scheduler.
`SELECT ... FOR UPDATE SKIP LOCKED` on the publish page; scheduler guard via Postgres
advisory lock (or ShedLock) so two producer instances can run.
**Done when:** a concurrency test with two simultaneous publishers shows each outbox row
published exactly once; [architecture.md](architecture.md) scaling note updated.

<a id="r8"></a>
### [ ] R8 — Mock PSP with a failure path
**Scope:** compose (+WireMock container), `BillingService`, revived `payment.provider.*`.
Consumer calls the mock over HTTP (baseUrl + timeout from config); provider
failure/timeout → `payment.status = 'failed'`, invoice/charge **not** finalized,
subscription not advanced; failure rate configurable via env.
**Done when:** failure-path integration test passes; verify.sh assertion changes from
"all succeeded" to "succeeded + failed == expected" (tightening: it now must also
assert failed count matches the configured rate ± tolerance).

<a id="r9"></a>
### [ ] R9 — Observability
**Scope:** both services.
Replace every `System.out.println` with SLF4J; Micrometer counters
(`outbox_inserted_total`, `outbox_published_total`, `renewals_processed_total{outcome}`)
and job/listener timers; expose the prometheus endpoint.
**Done when:** metric names are documented in [architecture.md](architecture.md);
verify.sh cross-checks `outbox_published_total` against the DB count.

<a id="r10"></a>
### [ ] R10 — Async job launch
**Scope:** `RenewalJobEndpoint`, launcher config.
Task-executor-backed `JobLauncher`; `POST` returns immediately with the execution id;
a `@ReadOperation` reports execution status.
**Done when:** trigger returns in <1s regardless of scale; verify.sh switches from a
long-blocking POST to trigger-then-poll-status.

<a id="r11"></a>
### [ ] R11 — Scale the scan and publish
**Scope:** `RenewalJobConfig`.
Replace the single-transaction `INSERT...SELECT` with keyset-paginated chunks; batch or
pipeline the publish side (today: one synchronous send per message).
**Done when:** a run against 1M due rows completes within container memory limits;
duration and heap recorded in [quality.md](quality.md).

<a id="r12"></a>
### [ ] R12 — Seed & load story
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

<a id="r14"></a>
### [ ] R14 — Migrate to Testcontainers 2.x
**Scope:** both module poms + test imports.
Docker Engine 29 (min API 1.44) rejects the API-1.32 fallback in Testcontainers
1.21.x's shaded docker-java; tests pass locally only via a machine-local
`~/.docker-java.properties` pin (`api.version=1.44`, noticed during [R3](#r3)). CI
runners will hit the same wall when they adopt Engine 29. Testcontainers 2.x renames
artifacts (`testcontainers-postgresql`, `-rabbitmq`, `-junit-jupiter`) and needs a
Spring Boot version with Testcontainers 2 support.
**Done when:** both suites are green on Testcontainers 2.x with the
`~/.docker-java.properties` pin deleted.
