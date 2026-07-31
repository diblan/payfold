# Payfold Billing & Payment Stack

This repository contains a local development stack for the Payfold billing engine
and payment service. The services run together via Docker Compose, seeded with
PostgreSQL data and wired to RabbitMQ so you can execute the renewal workflow
on-demand.

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

> **Era note:** the consumer numbers below were measured before
> [R23f](docs/roadmap.md#r23f) (2026-07-26), when a card consume settled
> synchronously inside the message handler. Since R23f a consume is a fast
> synchronous auth plus an asynchronous settlement on the shared spine, so
> queue-drain rate and end-to-end completion are related but distinct
> quantities; re-measuring both on the async pipeline is
> [R30](docs/roadmap.md#r30).

- **Producer (scan + publish):** 1,015,000 due renewals scanned and published in
  459 s wall at 183 MiB peak heap (1M-row run). At 100k the whole job takes ~22 s —
  a 330k night is roughly 2.5 minutes of publishing.
- **Consumer (bill + settle):** the documented 100k-due-today run drained all
  100,000 renewals in ~30 minutes — ~55/s overall, **~48/s sustained** after a
  ~2-minute warm-up burst. The current verifier requires every renewal to reach
  its token/IBAN-predicted terminal state through the shared asynchronous
  settlement spine and idempotent upsert chain.
  Adjacent runs measured 53/s (same day) and 41/s (against a 1M-deep queue).
- **Extrapolation:** at the measured 41–48/s, a 330k nightly batch drains in
  1.9–2.2 hours — **11–13× the 3.8/s average** the 10M/month target requires.

The single-thread consumer default is the binding constraint — and its two
scaling levers are measured, not promised (R20, 2026-07-26; both safe by design,
because idempotency lives in database unique constraints, not in consumer state):

- **Listener concurrency ×8** (one JVM, `CONSUMER_LISTENER_CONCURRENCY=8`):
  100k drained in **191 s — 524/s average, ~10× the baseline** — so a 330k
  nightly batch drains in ~10.5 minutes.
- **3 consumer replicas** (`docker compose up --scale renewal-consumer=3`,
  concurrency 1 each): 100k in 951 s — 105/s, ~2× baseline, with RabbitMQ
  round-robin splitting the work 33,335 / 33,320 / 33,345. Same-host replicas
  share one machine, so in-process concurrency is the cheaper local lever;
  replica scaling is the fault-tolerance and multi-node story (autoscaled
  across real hardware in the companion platform repo).

Reproduce it yourself:

```bash
# the documented run: 100k due-today subscriptions from a fresh boot
SEED_CUSTOMERS=100000 docker compose up -d --build
scripts/verify.sh --no-up --timeout 3600

# or: add due-today load to an already-running stack and measure the producer job
scripts/load-test.sh 50000
```

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
