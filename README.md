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
   - `mock-psp`: WireMock mock payment provider (port `8082`), declining a
     deterministic `PSP_FAIL_HEX` slice of renewals
   - `renewal-producer`: Spring Boot billing engine (port `8080`)
   - `renewal-consumer`: Spring Boot payment service (port `8081`)

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

- **Producer (scan + publish):** 1,015,000 due renewals scanned and published in
  459 s wall at 183 MiB peak heap (1M-row run). At 100k the whole job takes ~22 s —
  a 330k night is roughly 2.5 minutes of publishing.
- **Consumer (bill + settle):** the documented 100k-due-today run drained all
  100,000 renewals in ~30 minutes — ~55/s overall, **~48/s sustained** after a
  ~2-minute warm-up burst — with every renewal reaching its exact predicted
  terminal state, mock-PSP HTTP call and idempotent upsert chain included.
  Adjacent runs measured 53/s (same day) and 42/s (against a 1M-deep queue).
- **Extrapolation:** at the measured 42–48/s, a 330k nightly batch drains in
  1.9–2.2 hours — **11–13× the 3.8/s average** the 10M/month target requires.

The consumer is the binding constraint. Untested levers, listed as future work
rather than claims: listener concurrency and additional consumer instances — both
safe by design, because idempotency lives in database unique constraints, not in
consumer state.

Reproduce it yourself:

```bash
# the documented run: 100k due-today subscriptions from a fresh boot
SEED_CUSTOMERS=100000 docker compose up -d --build
scripts/verify.sh --no-up --timeout 3600

# or: add due-today load to an already-running stack and measure the producer job
scripts/load-test.sh 50000
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
  apply changes such as alternative ports or a different payment provider mock.

