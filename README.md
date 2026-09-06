# Payfold Billing & Payment Stack

This repository contains a local development stack for the Payfold billing engine
and payment service. The services run together via Docker Compose, seeded with
PostgreSQL data and wired to RabbitMQ so you can execute the renewal workflow
on-demand.

## Watch it run (under five minutes, no narration)

https://github.com/user-attachments/assets/f4df2a5d-10ed-4b65-841e-a35e09dc3768

One take of `scripts/chaos-demo.sh` on the unmodified Compose stack, cut only
for time, with the provisioned Grafana dashboard alongside. The nine scenes
are the ones described under [Run the chaos demo](#run-the-chaos-demo): the
pipeline draining, a poison message dead-lettering while good traffic flows,
a killed worker losing nothing, three replicas splitting the queue, a broker
restart, chargebacks under a slow bank, per-country routing lag, a recreated
counterparty recovered by the sweeper, and the dunning arc through `past_due`
to recovery and cancellation. Every `PASS` line on screen is an assertion the
script makes, not narration; waits are sped up and badged with their factor;
the figures the script quotes are the measured ones in
[Scale: measured, not claimed](#scale-measured-not-claimed), and the
dashboard beside the terminal shows what each scene claims
([R45](docs/roadmap.md#r45)). Recorded 2026-09-06 on v0.11.0 plus R45 and
re-recorded whenever the flow reshapes.

## For agents & contributors

This project is developed primarily with AI agents under a harness-engineering
workflow. Start with [AGENTS.md](AGENTS.md) — it maps the repo, the golden rules,
and the one-item-per-session protocol. The system docs live in [docs/](docs/)
(architecture, invariants, decisions, quality grades, roadmap), and
`scripts/verify.sh` is the one-command end-to-end check that defines "working".
This README stays a quickstart; everything deeper belongs in `docs/`.

## Prerequisites

- [Docker Desktop](https://docs.docker.com/get-docker/) or a Docker Engine that
  supports Compose v2 commands
- GNU Make (optional, only if you prefer to script the steps below)

## First-time setup

1. **Clone the repository** (or download and extract the archive) so the
   contents live under a single folder on your machine.
2. **Create the environment file.** Copy the provided example and adjust any
   values you need to customise (passwords, ports, timezone, etc.).

   ```bash
   cp .env.example .env
   ```

   The `.env` file is read by `docker compose` to inject configuration for
   PostgreSQL, RabbitMQ, and the Spring Boot applications. The defaults expose
   the most common ports:

   - PostgreSQL on `5432`
   - Renewal Producer actuator on `8080`
   - Renewal Consumer actuator on `8081`
   - RabbitMQ broker on `5672` and management UI on `15672`

## Running the stack

1. **Start the containers.** From the repository root, run:

   ```bash
   docker compose up --build
   ```

   The Compose file brings up the following services in order:

   - `postgres`: PostgreSQL 18 with the credentials supplied in `.env`
   - `flyway`: runs schema migrations stored in `db-migrations`
   - `seed-data`: executes the Java seed scripts in `seed-data-gen`, seeding
     `SEED_CUSTOMERS` customers (default 15000), each with a subscription due today
   - `rabbitmq`: RabbitMQ 3.13 with the management UI exposed locally
   - `mock-bank` / `mock-bank-b`: two instances of the same SEPA counterparty
     image; bank-a serves BE+FR on port `8085`, while deliberately slow bank-b
     serves NL+IE on port `8086`
   - `mock-card`: the same counterparty image in its card scheme (port `8087`);
     it returns token-derived authorization verdicts synchronously and sends
     settlement/chargeback events asynchronously
   - `renewal-producer`: Spring Boot billing engine (port `8080`)
   - `renewal-consumer`: Spring Boot payment service (port `8081`; scaled
     replicas bind up to `8083`)

   The Spring Boot services include health checks that keep retrying until their
   dependencies are ready, so the first boot can take a minute.

2. **Wait for the renewal producer to connect to RabbitMQ.** In a new terminal
   window, follow the logs for the producer container and wait until you see the
   connection confirmation:

   ```bash
   docker compose logs -f renewal-producer
   ```

   Once the connection is established you should see log lines similar to
   `Connection established to rabbitmq:5672`.

3. **Trigger the renewal job on demand.** The producer exposes a Spring Boot
   actuator endpoint that lets you bypass the scheduled cron and launch the job
   immediately. Run the following `curl` command:

   ```bash
   curl -v -X POST "http://localhost:8080/actuator/renewal-job?force=true" \
     -H "Content-Type: application/vnd.spring-boot.actuator.v3+json" \
     -H "Accept: application/json" \
     -d '{}'
   ```

   A `200 OK` response indicates the job was enqueued. You can watch the
   consumer logs (`docker compose logs -f renewal-consumer`) to see the payment
   processing in action.

## Scale: measured, not claimed

The design target is 10M subscription renewals a month — ≈330k/day, a 3.8/s
sustained average. Renewals are billed in one nightly batch, so the real requirement
is: scan and publish ~330k due renewals in one run, then drain the queue well before
the next run. Both sides are measured on a single-node WSL2 dev laptop running the
unmodified Compose stack; run details live in [docs/quality.md](docs/quality.md)
under "Measured scale runs".

Since the async settlement spine ([R23f](docs/roadmap.md#r23f)) a consume is a
fast synchronous auth/submission while settlement arrives later as an event, so
two quantities are measured ([R30](docs/roadmap.md#r30)): the renewals-queue
**drain rate** and **end-to-end completion** — every payment at its
token/IBAN-predicted terminal state, zero stuck `submitted`, including the
cohort that only completes through the recovery sweeper.

- **Producer (scan + publish):** 1,015,000 due renewals scanned and published in
  459 s wall at 183 MiB peak heap (1M-row run). At 100k the whole job takes ~22 s —
  a 330k night is roughly 2.5 minutes of publishing.
- **Consumer (bill + settle):** measured 2026-08-22 at 100k on the
  [D24](docs/decisions.md#d24) settlement relay: single consumer at
  concurrency 1 drains 100,000 renewals in **899 s — 111/s** with end-to-end
  completion — every payment terminal, recovery and the full dunning arc
  included — in **1,046 s**; the 15k demo cohort measures **195/s** (~6 ms
  per consume). Getting here twice cost a diagnosis each: a multi-worker
  uvicorn socket option let Nagle's algorithm tax every consume ~40 ms
  ([D23](docs/decisions.md#d23)), and the relay's serialized per-row
  confirms then capped the settlement spine below the fixed consume rate
  until [D24](docs/decisions.md#d24) pipelined them (relay lag p50 fell
  from 166 s to 0.27 s).
- **Extrapolation:** at the measured 100k single-consumer rate (111/s), a
  330k nightly batch is ~2.5 minutes of publishing plus **~50 minutes of
  draining — 29× the 3.8/s average** the 10M/month target requires; the
  concurrency-8 rate below clears the same batch in ~12 minutes.

The single-thread consumer default is the binding constraint — and its two
scaling levers are measured, not promised (re-measured 2026-08-22 at 100k on
the D24 relay; safe by design, because idempotency lives in database unique
constraints, not in consumer state):

- **3 consumer replicas** (`docker compose up --scale renewal-consumer=3`,
  concurrency 1 each): 100k drained in **388 s — 258/s, ~2.3× baseline** and
  fully settled in 474 s, with RabbitMQ round-robin splitting the work
  33,326 / 33,365 / 33,309 and three `SKIP LOCKED` relays sharing one inbox
  (fleet publish peak 440/s). Same-host replicas share one machine; replica
  scaling is the fault-tolerance and multi-node story (autoscaled across
  real hardware in the companion platform repo).
- **Listener concurrency ×8** (one JVM, `CONSUMER_LISTENER_CONCURRENCY=8`):
  100k drained in **219 s — 457/s** (618/s first minute, sagging as the
  same-JVM settlement spine competes) and fully settled in 529 s, with
  **zero dead-letters and zero submit timeouts** — the single relay absorbed
  the burst at sub-3 s lag (publish peak 687/s). On one host, in-process
  concurrency is the cheaper lever (457/s vs the fleet's 258/s), and the
  binding constraint has moved to the shared substrate: one Postgres
  absorbing every write and the per-JVM settlement side draining the
  backlog the burst builds. The pre-async 524/s figure was measured against
  a stub that did no outbound work. Measured, not claimed — cuts both ways.

Reproduce it yourself:

```bash
# the documented run: 100k due-today subscriptions from a fresh boot
SEED_CUSTOMERS=100000 docker compose up -d --build
scripts/verify.sh --no-up --timeout 3600

# or: add due-today load to an already-running stack and measure the producer job
scripts/load-test.sh 50000
```

## Dunning: failed collections get a lifecycle

Terminal payment failures don't dead-end. Every failure reason maps to a
dunning class (`retriable`, `hard_fail`, `dispute`), and any terminal failure
moves the subscription to `past_due` with a class-specific grace deadline.
Retriable failures re-collect on a schedule as new constraint-keyed payment
attempts through the normal settlement spine — a settled re-collection returns
the subscription to `active` — and stop at `DUNNING_MAX_ATTEMPTS` total
attempts, after which the sweeper cancels the subscription as exhausted.
Everything else is canceled when its grace deadline expires. All windows are
env-tunable seconds (fast for verify, visible for demo); `scripts/verify.sh`
predicts the exact end state of every seeded cohort from IBAN/token suffix
arithmetic alone — recovered, exhausted-canceled, or grace-expired-canceled —
and asserts the counts, and chaos-demo scene 9 shows the same arc live on the
dashboard.

## Run the chaos demo

One command demonstrates the failure modes the architecture exists for —
poison messages dead-lettering while good traffic flows, a killed worker
losing nothing and draining its backlog on recovery, three competing
consumers splitting the queue, and a broker restart absorbed without a
double-billed cent. Cards now authorize synchronously but settle asynchronously
through the same inbox → queue → listener spine as SDD. Scenes 1–5 use clean
card tokens so they isolate delivery behavior. Scene 6 switches the mock bank to
a slow profile so the submitted SDD backlog is visible, then proves MD06
chargebacks leave payments
`charged_back`, invoices `disputed`, settled charges intact, and subscriptions
advanced before restoring the fast profile. Scene 7 then sends equal clean SDD
cohorts through the country registry: BE clears through fast bank-a while NL is
still visibly `submitted` at slow bank-b, whose backlog subsequently drains.
Scene 8 recreates a counterparty container mid-flight — its in-memory state is
gone, and the pull-shaped recovery sweeper re-queries and resubmits until
nothing stays stranded. Scene 9 tells the dunning story end to end: a
suffix-95 SDD cohort fails its first collection, visibly parks `past_due` on
the dashboard gauge, and returns to `active` when its scheduled re-collection
settles, while a suffix-99 cohort that never settles makes exactly
`DUNNING_MAX_ATTEMPTS` bounded attempts and is canceled as exhausted — with
the cancellations-by-cause panel ticking and no further collection ever
attempted.
Every scene *asserts* its invariant (exact card/SDD per-row terminal states,
DLQ depths, per-replica counters, and per-bank lag) rather than just showing it;
watch it live on the provisioned Grafana dashboard at
`http://localhost:3000/d/payfold-pipeline`.

```bash
docker compose up -d --build     # if not already running
scripts/chaos-demo.sh            # paced; press enter between scenes
scripts/chaos-demo.sh --auto     # unattended, asserts everything
```

## Stopping and cleaning up

- Press `Ctrl+C` in the terminal running `docker compose up` to stop the stack.
- To remove containers, networks, and volumes created by Compose, run:

  ```bash
  docker compose down -v
  ```

  Removing volumes deletes the PostgreSQL and RabbitMQ data, so only use `-v`
  when you want a clean slate.

## Troubleshooting

- **RabbitMQ UI:** Visit [http://localhost:15672](http://localhost:15672) and
  sign in with the credentials from `.env` (defaults `guest`/`guest`) to inspect
  queues and messages.
- **Database access:** Connect to PostgreSQL via your favourite client using the
  `POSTGRES_*` settings defined in `.env`.
- **Configuration tweaks:** Update `.env` and re-run `docker compose up` to
  apply changes such as alternative counterparty ports, secrets, or delay profiles.
