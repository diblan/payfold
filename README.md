# Payfold Billing & Payment Stack

This repository contains a local development stack for the Payfold billing engine
and payment service. The services run together via Docker Compose, seeded with
PostgreSQL data and wired to RabbitMQ so you can execute the renewal workflow
on-demand.

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
   - `seed-data`: executes the Java seed scripts in `seed-data-gen` to populate
     reference data
   - `rabbitmq`: RabbitMQ 3.13 with the management UI exposed locally
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

