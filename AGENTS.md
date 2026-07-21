# AGENTS.md

Canonical guide for AI agents (and humans) working in this repo. A short map beats a
long manual: everything deeper lives in `docs/` and is versioned with the code.

## What this is

Payfold is a portfolio project demonstrating distributed-systems fundamentals: billing
10M Netflix-style subscription renewals a month via a transactional outbox, RabbitMQ,
and idempotent consumption. The docs are part of the deliverable — they prove
understanding to human reviewers as much as they guide agents.

## Repo map

| Path | What it is |
|---|---|
| `billing-engine/renewal-producer/` | Spring Batch job: scan due renewals → `renewal_outbox` → publish to RabbitMQ |
| `payment-service/renewal-consumer/` | Listener → `BillingService` upsert chain (invoice/charge/payment) |
| `db-migrations/` | Flyway V1–V4 — the **only** place schema changes happen |
| `seed-data-gen/` | JDBC seeders run by compose (`SEED_CUSTOMERS` customers, default 15k, all due today) |
| `mock-psp/` | WireMock mock PSP: decline-rule mapping templates (`.json.tpl`) rendered by the compose entrypoint |
| `docker-compose.yaml` | Full local stack: postgres, flyway, seed, rabbitmq, mock-psp, both services |
| `scripts/verify.sh` | End-to-end check; **the definition of "working"** |
| `docs/architecture.md` | System map, message flow, scale math, config truth table |
| `docs/invariants.md` | Golden rules G1–G8 with HELD/VIOLATED status |
| `docs/decisions.md` | Decision log D1–D8 |
| `docs/quality.md` | Module grades A–D, re-graded on touch |
| `docs/roadmap.md` | Non-goals + the ordered work queue R1–R15 |
| `notes/` | Gitignored private scratch (source articles); **never commit or quote verbatim** |

## Run & verify

```bash
cp .env.example .env          # first time only
docker compose up -d --build
scripts/verify.sh             # exit 0 = the system works
```

A change is not done until `scripts/verify.sh` passes. It may only ever be made
stricter (G7).

## Golden rules (one-liners — canonical text in docs/invariants.md)

- **G1** Renewal messages reach RabbitMQ only via the outbox.
- **G2** Consumer idempotent under redelivery at ANY time; keys from message content, never consume-time clock.
- **G3** Schema changes only via a new Flyway migration; applied migrations are immutable.
- **G4** Money is integer cents + currency, always.
- **G5** Unprocessable messages reach the DLQ in bounded attempts — no infinite requeue, no silent drop.
- **G6** Docs update in the same PR as the behavior they describe.
- **G7** `verify.sh` is a ratchet: tighten, never loosen.
- **G8** The message payload is a versioned, additive contract.

## Session protocol — how work stays focused

1. **One roadmap item per session.** Open `docs/roadmap.md`, take the top-most
   unblocked unchecked item (unless the user names another). Nothing else.
2. **No drive-by fixes.** A defect noticed en route becomes a new roadmap item with
   acceptance criteria — not an edit in this session.
3. **Definition of done**, all in the same PR:
   - both modules build; `scripts/verify.sh` exits 0;
   - the item's acceptance criteria hold;
   - `docs/quality.md` re-graded for touched modules; roadmap item checked off;
   - `docs/architecture.md` updated if flow/schema/config changed (G6).
4. **Exemption:** sessions touching only `docs/`, `AGENTS.md`, or `README.md` with no
   behavior change need no roadmap slot.
5. **Scope pressure?** If a task seems to require breaking a non-goal
   (`docs/roadmap.md#non-goals`), stop and require a `docs/decisions.md` entry first.

## Non-goals (summary — full list with rationale in docs/roadmap.md)

No Kubernetes. No real PSP or money movement. No auth/tenancy. No UI. No dunning,
proration, refunds, or tax. No event-sourcing rewrite. No third service without a
decision entry.

## Read-when index

| Before you… | Read |
|---|---|
| start any session | `docs/roadmap.md` |
| change behavior at all | `docs/invariants.md` |
| touch message flow, schema, or config | `docs/architecture.md` |
| propose an alternative design | `docs/decisions.md` |
| pick a cleanup target | `docs/quality.md` |
