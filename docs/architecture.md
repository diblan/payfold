# Architecture

System map for Payfold. Read this before touching the message flow, schema, or config.
When behavior changes, this file changes in the same PR ([G6](invariants.md#g6)).

## What Payfold demonstrates — and what it doesn't (yet)

The claim is "a system that can bill 10M subscription renewals a month".
10M/month ≈ 330k/day ≈ 3.8/sec sustained — but renewals are billed in a daily batch,
so the real requirement is: scan and publish ~330k due renewals in one nightly run,
and consume them at a rate that drains the queue well before the next run.

Honesty table:

| Property | Status |
|---|---|
| Transactional outbox (atomic scan + publish intent) | Demonstrated |
| Broker-acknowledged publish (publisher confirms + returns) | Demonstrated — confirm-gated `published_at`, unconfirmed-repick test; mandatory publishing with publisher returns treats an unroutable (returned) message as unconfirmed, proven by a binding-less-exchange test ([R15](roadmap.md#r15)) |
| Idempotent redelivery handling | Demonstrated — payload-keyed; covered by a cross-midnight integration test, see [G2](invariants.md#g2) |
| Bounded failure handling (DLQ) | Demonstrated — bounded listener retry + DLQ routing; poison-path integration test and `verify.sh` probe; see [G5](invariants.md#g5) |
| Two-method counterparty path | Demonstrated — SDD and cards share one async settlement spine; card authorization declines synchronously while authorized cards and SDD finalize only from settlement events; exact token/IBAN outcome assertions |
| Operational observability | Demonstrated — SLF4J logging, Prometheus counters + built-in job/listener timers, `verify.sh` cross-checks metric deltas against DB deltas |
| Throughput at 330k/day | Measured end to end on the async spine — producer: 1,015,000 due rows scanned + published in 459 s wall, peak heap 183 MiB; consumer at conc-1: **195/s measured 2026-08-08** at the 15k demo scale (listener ~6 ms/consume after [D22](decisions.md#d22)/[D23](decisions.md#d23); the dated 100k record is 62/s pre-fix, [R30](roadmap.md#r30)) with end-to-end completion 163 s including recovery and the dunning arc. Even at the conservative dated 62/s a 330k night is ~2.5 min of publishing plus ~1.5 h of draining — 15–16× the 3.8/s requirement — and the post-fix rate triples that headroom pending the [R39](roadmap.md#r39) 100k re-measure. Single-node WSL2 dev-laptop numbers (see quality.md "Measured scale runs") |
| Horizontal producer scaling | Demonstrated — `FOR UPDATE SKIP LOCKED` page claims + advisory-lock cron guard; exactly-once under two concurrent publishers proven by test (compose still runs a single producer instance) |
| Failed-payment lifecycle (dunning) | Demonstrated — every terminal reason maps to a class (`retriable`/`hard_fail`/`dispute`), retriable failures re-collect on schedule through the normal spine, and bounded exhaustion or grace expiry ends in cancellation; `verify.sh` asserts the exact cohort matrix from suffix arithmetic ([R26a](roadmap.md#r26a)–[R26d](roadmap.md#r26d)) |
| Consumer scaling levers | Measured on the async spine, 2026-08-01 (both lever numbers predate the [D23](decisions.md#d23) stall fix — re-measured under [R39](roadmap.md#r39)) — 3 same-host replicas at concurrency 1: 100k drained in 642 s (156/s; RabbitMQ round-robin split 33,338/33,307/33,355), end-to-end settled in 675 s, full verify.sh-green with fleet-summed exact checks. Listener concurrency ×8 ([R32](roadmap.md#r32)): 100k drained in 638 s (157/s), settled in 1,739 s, zero dead-letters and zero submit timeouts on the [D19](decisions.md#d19) design — a ceiling now known to have been set largely by the ~44 ms per-request counterparty stall D23 removed (8 threads ÷ 44 ms ≈ 180/s), not by the substrate; the retired 524/s figure was WireMock-era. Same-host replicas contend for one machine; true multi-node scaling is the external platform repo's KEDA demonstration |

## Component map

```
                 ┌─────────────┐   Flyway V1–V11   ┌──────────────┐
                 │   flyway    ├──────────────────▶│              │
                 └─────────────┘                   │  postgres:18 │
                 ┌─────────────┐  SEED_CUSTOMERS   │   (payfold)  │
                 │  seed-data  ├──────────────────▶│              │
                 └─────────────┘                   └──────┬───────┘
                                                          │
   cron 03:00 ──▶┌──────────────────────┐    scanStep     │
   or POST       │   renewal-producer   │ keyset pages/tx │
   /actuator/    │   (Spring Batch)     ├────────────────▶│  renewal_outbox
   renewal-job   │   :8080              │    publishStep  │
                 └──────────┬───────────┘  page 10000/tx  │
                            │ publish + mark published_at │
                            ▼                             │
                 ┌──────────────────────┐                 │
                 │ RabbitMQ 3.13        │                 │
                 │ exchange:            │                 │
                 │  billing.renewals    │                 │
                 │  rk renewal.requested│                 │
                 │  └▶ queue            │                 │
                 │     billing.renewals │                 │
                 │     .main            │                 │
                 │                      │                 │
                 │ DLX billing.renewals │                 │
                 │ .dlx ─▶ DLQ (rk dlq) │                 │
                 │ [reachable since R5] │                 │
                 └──────────┬───────────┘                 │
                            ▼                             ▼
                 ┌──────────────────────┐   invoice / charge /
                 │   renewal-consumer   ├─▶ payment / subscription
                 │   :8081-83 (host)    │   upserts via unique
                 └──────────┬───────────┘   constraints
                            └── POST /collections ──▶ mock-counterparty ×3
                                                      bank-a :8085 — SEPA / BE,FR
                                                      bank-b :8086 — SEPA / NL,IE
                                                      cardnet :8087 — card auth
                                                        + async settlement
```

### Settlement spine (R23e)

```
mock-counterparty ×3 ── signed POST /webhooks/bank/{id} ──▶ renewal-consumer
                                                      │
                                                      ▼
                                             settlement_inbox
                                             (unique bank + notification;
                                              row is also its outbox)
                                                      │ confirm-gated relay
                                                      ▼
                                      billing.settlements.main ──▶ listener
                                               │                      │
                                               ▼                      ▼
                                      billing.settlements.dlq   payment / charge /
                                                               invoice / subscription
```

## The renewal job (producer)

Job `renewalJob` = `scanStep` → `publishStep`, defined in
`billing-engine/renewal-producer/.../job/RenewalJobConfig.java`.

Two ways to launch, both build the identifying parameter `scheduleDate = today`:

- **Cron** — `RenewalScheduler`, `${app.scheduleCron}` (default `0 0 3 * * *`, zone
  `${app.timezone}`). Cron launches are serialized across producer instances by a
  Postgres session advisory lock; a losing instance logs a one-line skip without
  creating a second `JobExecution`. If a peer already ran today's `scheduleDate`
  before the lock is acquired, the JobRepository rejection is caught and skipped
  cleanly. One dedicated database connection holds and explicitly releases the lock
  for the whole job; if the JVM dies, its session dies and Postgres releases the lock
  automatically, so no lease table is needed.
- **On demand** — `POST /actuator/renewal-job?force=true` (`RenewalJobEndpoint`).
  `force=true` adds a random `run.id` so the same day can be re-run; without it,
  Spring Batch rejects a repeated job instance for the same `scheduleDate`.
  The force path deliberately remains outside the scheduler guard: it is the
  operator's manual override, each force run is a distinct job instance, and
  `SKIP LOCKED` keeps concurrent runs row-safe.
  Since [R10](roadmap.md#r10) the endpoint is **asynchronous**: the POST launches
  the job on a dedicated single-thread executor via the endpoint-only
  `asyncJobLauncher` bean and returns the `executionId` immediately;
  `GET /actuator/renewal-job/{executionId}` reports live status from the
  `JobExplorer` (unknown id → 404). The cron path keeps Batch's default
  synchronous `jobLauncher` deliberately: the scheduler releases its advisory
  lock when `run()` returns, so an async launch there would release the lock
  mid-job and undo the cross-instance serialization above ([D9](decisions.md#d9)).
  The single launcher thread queues concurrent force-triggers so they run
  serially (a queued run reports `STARTING` until the thread frees).

**scanStep** — a tasklet re-run per page (`RepeatStatus.CONTINUABLE`), one
transaction per page, keyset-paginated over the primary key: each iteration
selects the next `${app.scanPageSize}` (default 10000) active subscriptions with
`s.id > cursor ORDER BY s.id LIMIT n`, applies the due-window filter
(`renewed_at` + plan interval falling in today's local-day window) *inside* the
page, and inserts the page's due rows into `renewal_outbox` with
`ON CONFLICT (subscription_id, due_date) DO NOTHING` (constraint
`uniq_outbox_sub_due`), so re-scans and crash-resumed scans stay idempotent
across pages. The page query reports its own row count and last id regardless of
how many rows were due, so all-not-due pages still advance the cursor; the loop
ends when a page comes back short, never on `inserted == 0`. The cursor — and
the due window, fixed at the first page so a scan crossing midnight keeps one
consistent window — is carried in the step ExecutionContext, which Spring Batch
persists in the same transaction as the page's inserts, so a restart resumes
from the last committed page. Each row's payload is the full
[`renewal.requested` v1 contract](#message-contract--renewalrequested-v1),
including the producer-minted identity, idempotency key, due date, and billing
period. Scanning keyset-over-all-actives instead of indexing the due predicate
is [D10](decisions.md#d10).

**publishStep** — tasklet re-run per page (`RepeatStatus.CONTINUABLE`), one page per
transaction: select `LIMIT ${app.publishPageSize}` (default 10000, raised from 1000 by [R11](roadmap.md#r11) so 1M rows publish in ~100 page transactions instead of ~1000) unpublished rows ordered
by `id` with `FOR UPDATE SKIP LOCKED`, so concurrent publishers claim disjoint pages,
then publish each via `OutboxPublisher` with correlated publisher confirms
(`spring.rabbitmq.publisher-confirm-type: correlated`) and the outbox row id as the
correlation id. The row locks intentionally span the confirm await inside the page
transaction, bounded by one `app.confirmTimeoutMs` deadline (default 10s); this keeps
in-flight rows invisible to peers until `published_at` is updated and the transaction
commits. Under READ COMMITTED, the lock-time recheck drops a row that a peer published
and committed while the statement was acquiring locks. An empty page means none are
visible to this publisher — the outbox is drained or the remainder is peer-claimed —
and the tasklet finishes rather than busy-waiting. That is safe because claims last only
for the transaction: a peer either publishes its rows or its locks end with its failed
transaction and a later page or job run re-picks them. Unconfirmed rows stay NULL and
are re-picked by the next page or job run: delivery is at-least-once, and consumer
idempotency absorbs duplicates ([G2](invariants.md#g2)). A page with zero confirmed rows
fails the job, making the failure visible instead of tight-looping over the same page.
A confirm alone only proves the exchange accepted the message: with no queue bound, the
broker confirms and silently drops it. Publishing is therefore `mandatory` with
publisher returns enabled ([R15](roadmap.md#r15)): the broker sends `basic.return`
before the `basic.ack` for an unroutable message, spring-rabbit populates the
correlation's returned message before completing the confirm future, and
`OutboxPublisher` reports any returned message as unconfirmed — the return wins over
the ack — so its row keeps `published_at` NULL and is re-picked. A fully-returned page
ends in the zero-progress job failure above. Every returned message is logged at WARN
with its routing key and counted by `outbox_returned_total`.

Within a page, sends are pipelined but never more than `app.publishInFlightLimit`
(default 100) may be awaiting confirms at once ([R25](roadmap.md#r25)): spring-rabbit
parks a channel whose confirms are pending instead of re-caching it, so each
unconfirmed in-flight send holds one broker channel, and unbounded pipelining under a
slow-confirming broker opens a channel per send until RabbitMQ's `channelMax` (2047)
kills the page with zero confirms. The window caps the page's channel budget;
`spring.rabbitmq.cache.channel.size` is kept equal to the limit so a parked channel
re-enters the cache when its confirm arrives and is reused rather than churned. A
window stalled to the `app.confirmTimeoutMs` deadline stops sending — unsent rows
simply stay unpublished for re-pick — and the zero-progress failure above is
unchanged. Bounding via `channelCheckoutTimeout` instead was rejected
([D14](decisions.md#d14)).

Producer declares only the exchange (`RabbitConfig`); the consumer owns the rest of the topology.

## The consumer

`payment-service/renewal-consumer/.../mq/RenewalListener.java` listens on
`billing.renewals.main`, deserializes to the `RenewalRequested` record, and calls
`BillingService.process`, which runs an upsert chain — each step backed by a DB
unique constraint (this *is* the idempotency mechanism, [D2](decisions.md#d2));
since [D22](decisions.md#d22) the invoice/charge/payment upserts commit as one
transaction, so the chain pays one WAL flush instead of three autocommits while
the constraints keep arbitrating idempotency:

| Step | Table | Payload material / backing constraint |
|---|---|---|
| `upsertInvoice` | `invoice` | `period_start`, `period_end`; `uniq_invoice_period (customer_id, period_start, period_end, currency)` |
| `upsertCharge` | `charge` | actual `due_date`; `uniq_charge_period (subscription_id, due_date, amount_cents, currency)` |
| `upsertPayment` | `payment` | `idempotency_key`; `payment.idempotency_key UNIQUE` |
| `BankClient` submission | — | HTTP `POST /collections` to the registry entry selected by method/country |
| settlement listener | `payment`, `charge`, `invoice`, `subscription` | finalizes authorized cards and SDD only from `settlement.received` |

After the shared invoice, charge, and payment upserts, the customer's payment
method selects a registry scheme. The fail-fast registry contains exactly one
`card` entry and one or more `sepa_core` entries. SDD maps the customer's
uppercase-normalized country to a SEPA entry; card uses the singleton card entry.
Startup rejects incomplete entries, unsupported schemes, duplicate ids/country
claims, missing SEPA countries, or zero/multiple card entries. An unroutable SDD
country is a deterministic renewal violation and takes the no-retry path to the
DLQ.

Both methods submit `POST /collections` with `collection_id` equal to the payment
idempotency key. SDD acceptance and card authorization mark the payment
`submitted` with `bank_id` and `collection_id`, finalize nothing, and ACK the
renewal. A card decline is the one terminal synchronous verdict: it marks the
payment `failed` with the card reason and never receives a settlement leg. A
transport failure or malformed response is the absence of a verdict, never a
guessed decline; it throws and rides the R5 bounded listener retry to the DLQ
([G5](invariants.md#g5)). Duplicate submissions return the counterparty's stored
answer and schedule nothing, healing the crash window between submission and the
payment status update.

The payment status vocabulary is `pending`, `submitted`, `succeeded`, `failed`,
and `charged_back`. The subscription vocabulary is `active`, `paused`,
`past_due`, and `canceled`. The invoice vocabulary is `draft`, `posted`, `paid`,
`disputed`, and `void`.

The settlement spine closes that asynchronous submission loop. `POST
/webhooks/bank/{bankId}` first rejects an unknown bank as `404`, then verifies
the HMAC-SHA256 signature over the exact request bytes (`401` on a missing or
mismatched signature), then validates the required notification identity
(`400` for unparseable or incomplete JSON). A valid callback returns `200` only
after its raw payload is durably inserted into `settlement_inbox`; uniqueness on
`(bank_id, notification_id)` makes bank redelivery a no-op that still returns
`200`. The path bank id, whose secret was verified, is the trusted bank
attribution.

A consumer-side recovery sweeper makes this path pull-shaped as well as pushed:
it pages through stale `submitted` payments under a transaction-scoped advisory
lock and re-queries the owning counterparty. A stored sequence-1 outcome is
synthesized into the same `settlement_inbox`, whose unique constraint safely
dedupes a late webhook; a `404` means restart amnesia and resubmits the same
collection id, safe because the counterparty's outcome is deterministic. This
mirrors pull-shaped SEPA reporting and prevents a missed push from stranding a
payment ([D18](decisions.md#d18)).

Each inbox row is also its own outbox. A scheduled relay claims up to
`relay.page-size` unpublished rows (default 500, one page per 500 ms tick) with
`FOR UPDATE SKIP LOCKED`, so replicas safely compete, normalizes each payload
to `settlement.received` v1, and pipelines the page's sends behind a bounded
in-flight confirm window (`relay.in-flight-limit`, default 100 — the producer's
publish-window shape, inbound; [D24](decisions.md#d24)). One page-scoped
deadline (`relay.confirm-timeout-ms`, default 5 s) bounds the send loop and the
confirm await together; `published_at` is then batch-marked for exactly the
acked rows. A nack, stall, timeout, or exception is loud (WARN) and leaves its
rows unpublished for the next tick. Pipelined sends ride multiple channels, so
strict page FIFO is not guaranteed — arrival-order independence is the
settlement listener's contract (below). That confirm/crash window is
deliberately at-least-once, with queue-side idempotency absorbing duplicates.

`SettlementListener` consumes the fixed `billing.settlements.main` queue.
`SettlementService` accepts state transitions only through SQL guards with
status predicates: `settled` changes a `submitted` payment to `succeeded`, then
settles the charge, pays the invoice, and advances the subscription to the
invoice's `period_end` at 09:00 local; `failed` records terminal `failed`,
`failure_reason`, and `completed_at` without finalizing billing. A
`charged_back` notification changes either `submitted` or `succeeded` to
`charged_back`, records its reason and `charged_back_at`, and wins in either
arrival order. When the prior state was `succeeded`, the already-paid invoice
becomes `disputed`; when chargeback arrives first, the invoice remains `posted`
and a later settlement no-ops. In both cases the charge and subscription period
math remain untouched: a chargeback is a recorded fact, not compensation
([D16](decisions.md#d16)). Terminal payment-status guards make every settlement
redelivery a no-op.

### Dunning grace lifecycle (R26a–R26c)

Every terminal collection reason maps through the consumer's yaml-fixed policy
to exactly one class: `retriable` (`AM04`, `insufficient_funds`), `hard_fail`
(`AC04`, `MD01`, `do_not_honor`), or `dispute` (`MD06`, `fraud_dispute`). A
guarded terminal `failed` or `charged_back` settlement, and the synchronous
card-decline branch, move an `active` subscription to `past_due` and set its
single `grace_until` deadline from that class's env-tunable window. Unknown
reasons conservatively use `hard_fail`. The `status = 'active'` update guard
makes duplicate hooks and redelivered terminal messages leave the first deadline
untouched; transition counters increment only when that update succeeds.

The dunning sweeper re-collects retriable failures on the
`DUNNING_RETRY_DELAY_SECONDS` cadence as new, constraint-keyed payment rows through
the same submission spine (`sub-<id>|<due_date>|a<attempt>`). A settled
re-collection returns the subscription to `active` and clears `grace_until`;
`submitted` rows are invisible to dunning, which is the [R28](roadmap.md#r28)
boundary between received failure signals and missing settlement recovery.

Enforcement ([R26c](roadmap.md#r26c), [D21](decisions.md#d21)) is a second act
on the same sweeper tick: a subscription whose latest attempt failed retriably
at `DUNNING_MAX_ATTEMPTS` is canceled as *exhausted*, and any `past_due`
subscription whose `grace_until` has passed is canceled as *grace-expired* —
the class-blind backstop. Cancellation clears `grace_until`, is terminal and
idempotent by predicate (`canceled` is invisible to the retry picker, to grace
entry, and to recovery), and increments
`dunning_cancellations_total{cause=exhausted|grace_expired}`.

This lifecycle changes status only. It never reverses `renewed_at`, so the
[R23d](roadmap.md#r23d) period advance remains a recorded fact after a
chargeback. R26a recorded the deadline; [R26b](roadmap.md#r26b) added scheduled
re-collection and [R26c](roadmap.md#r26c) added bounded exhaustion and grace
expiry — the lifecycle is complete.

Card authorization declines are business failures: the payment becomes `failed`,
the subscription enters the same grace lifecycle, the renewal is ACKed, and no
settlement notification follows. Renewal redelivery never re-attempts a failed
payment — re-collection is exclusively the dunning sweeper's, which retries card
failures through the same submission spine as SDD.
Authorized cards and SDD stay `submitted` until the settlement listener decides
their terminal state.

The consumer uses validated payload values only and has no clock-derived fallbacks.
Missing or invalid required fields throw `InvalidRenewalMessageException`; deterministic
contract violations skip retry and dead-letter immediately.

**Topology** (`RabbitTopology`): the main queue has `x-dead-letter-exchange:
billing.renewals.dlx` and `x-dead-letter-routing-key: dlq`, matching the DLQ binding.
The listener makes at most five attempts with exponential backoff from 1s to a 10s cap
at a 2.0 multiplier, then `default-requeue-rejected: false` lets RabbitMQ dead-letter
the rejected message. `InvalidRenewalMessageException` is non-retryable and goes
straight to the DLQ; other failures use the bounded retry budget. Queue arguments are
immutable, so brokers carrying the pre-R5 queue must delete it or wipe the RabbitMQ
volume before redeclaration; [D4](decisions.md#d4) records why the queue name stayed.

`SettlementTopology` repeats the D4 shape with fixed internal-contract names:
direct exchange `billing.settlements`, routing key `settlement.received`, main
queue `billing.settlements.main`, DLX `billing.settlements.dlx`, and DLQ
`billing.settlements.dlq` bound with `dlq`. Both listener types share the same
bounded retry customizer; deterministic renewal and settlement contract
violations skip directly to their respective DLQ.

<a id="mock-bank"></a>
## Mock counterparty (banks + card scheme)

The FastAPI mock counterparty is the asynchronous settlement counterparty selected
by [D15](decisions.md#d15) and generalized by [D17](decisions.md#d17). Compose
runs three instances of the same image: `bank-a` serves BE/FR on a fast
`sepa_core` profile, `bank-b` serves NL/IE on a deliberately slow `sepa_core`
profile, and `cardnet` runs the `card` scheme on port 8087. Identity, callback
URL, shared secret, and delays are per-instance environment only.

The old WireMock PSP and its `/psp/charges` contract are retired. The one
counterparty image now covers both methods, and both fulfillment paths emit the
same signed settlement contract through the same durable spine. WireMock remains
only a generic test-time HTTP stub in Java integration tests.

`POST /collections` selects a request model by scheme. `sepa_core` accepts:

| Field | JSON type | Semantics |
|---|---|---|
| `collection_id` | string | Stable submission identity; duplicate submissions are idempotent |
| `amount_cents` | integer | Positive amount in integer minor units ([G4](invariants.md#g4)); floats and strings are rejected |
| `currency` | string | Three uppercase letters paired with `amount_cents` ([G4](invariants.md#g4)) |
| `debtor_iban` | string | Debtor account; the deterministic rule keys on its last two characters |
| `mandate_reference` | string | Creditor's non-empty SDD mandate reference |
| `due_date` | string | Collection due date in ISO `YYYY-MM-DD` form |

A new collection returns `202` with `status: accepted` and `duplicate: false`,
stores its classified outcome and notification states, and schedules delivery.
Repeating a `collection_id` returns `200` with `duplicate: true` and schedules
nothing. `GET /collections/{collection_id}` returns the full stored record: the
submitted fields, top-level classification and response fields, plus the stored
notification plan:

```json
{
  "collection_id": "collection-123",
  "amount_cents": 1499,
  "currency": "EUR",
  "debtor_iban": "BE68000000601701",
  "mandate_reference": "MNDT-6017",
  "due_date": "2026-08-01",
  "outcome": "settled",
  "reason": null,
  "response_status": "accepted",
  "response_reason": null,
  "notifications": [
    {
      "seq": 1,
      "outcome": "settled",
      "reason": null,
      "notification_id": "collection-123:1",
      "state": "scheduled"
    }
  ]
}
```

Notification state is one of `scheduled`, `delivered`, `gave_up`, or
`suppressed`. For card records the request carries `card_token` instead of SDD
debtor fields, the top-level `outcome` remains the authorization verdict, and
settlement is represented only in `notifications[]`. `/health` reports the bank
identity and scheme. `/metrics` exposes the Prometheus counters
`bank_collections_received_total`, `bank_webhook_delivered_total`, and
`bank_webhook_giveups_total`.

The IBAN rule is deliberately small and deterministic:

| Last two IBAN characters | Classification | Wire notification(s) | ISO 20022 reason |
|---|---|---|---|
| `99` | failed | `failed` | `AM04` — insufficient funds |
| `98` | failed | `failed` | `AC04` — account closed |
| `97` | failed | `failed` | `MD01` — no valid mandate |
| `96` | settled then charged back | `settled`, then `charged_back` | `MD06` — payer objection after settlement |
| `95` | fails, then settles on re-collection | attempt 1 `failed`; attempt ≥ 2 `settled` | `AM04` — insufficient funds, recoverable |
| `94` | settled | `settled` suppressed | none |
| any other suffix | settled | `settled` | none |

Suffix `94` classifies normally but never notifies, modeling deliverability loss
as a deterministic rule ([D18](decisions.md#d18)).
Suffix `95` is attempt-indexed off the collection id's `|a<attempt>` tail — the
verdict needs no stored history, so it survives restart amnesia and worker hops
([D20](decisions.md#d20)).

Each rule-bearing suffix is 1% of a uniform two-digit tail. More importantly,
later verifier sub-items can predict every result directly in SQL as
`right(debtor_iban, 2)` rather than trusting an aggregate percentage.

The `card` scheme accepts:

| Field | JSON type | Semantics |
|---|---|---|
| `collection_id` | string | Stable submission identity; duplicates return the stored verdict |
| `amount_cents` | integer | Positive amount in integer minor units ([G4](invariants.md#g4)) |
| `currency` | string | Three uppercase letters paired with `amount_cents` |
| `card_token` | string | Tokenized card reference; the auth rule keys on its last two characters |
| `due_date` | string | Due date in ISO `YYYY-MM-DD` form |

Card submission answers with a synchronous authorization verdict. A decline is
HTTP `200`, stored as the immutable verdict, schedules no notification, and
becomes terminal immediately in the consumer. Authorization is HTTP `202`; the
payment parks `submitted`, then settlement and any later chargeback arrive
through the same webhook/inbox/queue/listener spine as SDD.

| Last two token characters | Sync verdict | Async notification(s) | Reason |
|---|---|---|---|
| `99` | declined | none | `insufficient_funds` |
| `98` | declined | none | `do_not_honor` |
| `96` | authorized | `settled`, then `charged_back` | `fraud_dispute` on chargeback |
| `95` | declined, then authorized on re-collection | attempt ≥ 2 `settled` | `insufficient_funds` on attempt 1 |
| `94` | authorized | `settled` suppressed | none |
| any other suffix | authorized | `settled` | none |

Suffix `94` classifies normally but never notifies, modeling deliverability loss
as a deterministic rule ([D18](decisions.md#d18)).
Suffix `95` is attempt-indexed off the collection id's `|a<attempt>` tail — the
verdict needs no stored history, so it survives restart amnesia and worker hops
([D20](decisions.md#d20)).

Repeating a card `collection_id` returns HTTP `200` with the same stored
`authorized` or `declined` verdict and `duplicate: true`; it never schedules an
extra notification.

Webhook delivery sends the exact compact JSON bytes that were signed. Headers
are `Content-Type: application/json`, `X-Bank-Id: <bank_id>`, and
`X-Bank-Signature: sha256=<hex HMAC-SHA256 of the exact raw body>`. The
versioned payload has seven fields:

```json
{
  "schema_version": 1,
  "bank_id": "bank-a",
  "notification_id": "collection-123:1",
  "collection_id": "collection-123",
  "outcome": "settled",
  "reason": null,
  "occurred_at": "2026-07-26T12:00:00+00:00"
}
```

Notification ids are deterministic: `<collection_id>:<seq>`. Sequence 1 is
sent after the settlement delay; a chargeback at sequence 2 adds the configured
chargeback lag. Non-2xx responses and HTTP transport errors retry with bounded
exponential backoff. Exhausting the attempt cap gives up loudly with an ERROR
log carrying the bank, notification, collection, URL, and attempt count, plus
an increment of `bank_webhook_giveups_total`.

The generic image is configured per instance with compose-only `BANK_*`
environment variables; there is no `application.yaml`:

| Environment variable | Default | Purpose |
|---|---|---|
| `BANK_ID` | `bank-a` | Bank identity included in notifications and headers |
| `BANK_SCHEME` | `sepa_core` | Counterparty behavior scheme (`sepa_core` or `card`) |
| `BANK_WEBHOOK_URL` | `http://renewal-consumer:8080/webhooks/bank/bank-a` | Notification target |
| `BANK_WEBHOOK_SECRET` | `payfold-dev-secret` | Per-bank HMAC shared secret |
| `BANK_SETTLEMENT_DELAY_SECONDS` | `2.0` | Delay before sequence 1 |
| `BANK_CHARGEBACK_LAG_SECONDS` | `5.0` | Additional delay before sequence 2 |
| `BANK_WEBHOOK_RETRY_MAX_ATTEMPTS` | `5` | Bounded delivery-attempt cap |
| `BANK_WEBHOOK_RETRY_BACKOFF_SECONDS` | `0.5` | Initial exponential-retry delay |
| `BANK_WORKERS` | `1` | uvicorn worker count; compose raises cardnet's via `CARDNET_WORKERS` (default 4). Worker-safe without shared state: outcomes and notification ids are deterministic, so a cross-worker miss replays the amnesia paths ([D19](decisions.md#d19)) |

The SEPA delivery surface is an explicit PSP-style fiction: real banks commonly
report over file channels such as EBICS, with pain.002 and camt.054 batches.
Payfold borrows that vocabulary while emitting JSON webhooks to keep the
distributed-systems behavior inspectable. The exercised `BANK_SCHEME` seam is
what lets cards and SDD share one image without pretending their first signal has
the same semantics. Bank `/metrics` aggregates across workers via
prometheus_client's multiprocess collector (`PROMETHEUS_MULTIPROC_DIR`, baked into
the image); Prometheus does not scrape the banks — the endpoint serves verify.sh
and live forensics, which need the fleet sum.

## Observability

Both services log through SLF4J, with Logback supplied by Spring Boot's defaults.
Normal batch progress and coordination skips are INFO; an unconfirmed publish page and
each broker-returned (unroutable) message are WARN.
A declined payment is INFO because it is an expected business outcome whose
signal is the outcome counter.

The Prometheus name is the external contract used by the roadmap and `verify.sh`.
Micrometer converts dots in meter names to underscores for Prometheus and appends
`_total` to counter names.

| Micrometer meter | Prometheus name | Type | Tags | Incremented |
|---|---|---|---|---|
| `outbox.inserted` | `outbox_inserted_total` | Counter | none | By the number of rows inserted immediately after the scan SQL update |
| `outbox.published` | `outbox_published_total` | Counter | none | By the number of confirm-gated rows immediately after their `published_at` batch update |
| `outbox.returned` | `outbox_returned_total` | Counter | none | Once per message the broker returned as unroutable, inside the confirm-future completion that reports the row unconfirmed |
| `renewals.processed` | `renewals_processed_total{outcome="...",method="..."}` | Counter | `outcome=succeeded \| failed \| invalid \| submitted`; `method=card \| sdd \| unknown` | Per renewal delivery at its decision point; invalid messages without a known customer use `unknown`, and authorized card/SDD submissions count as submitted |
| `settlements.processed` | `settlements_processed_total{outcome="...",bank="..."}` | Counter | `outcome=settled \| failed \| charged_back \| invalid`; `bank=bank-a \| bank-b \| cardnet \| unknown` | Per settlement delivery at validation or its accepted outcome; terminal redeliveries count as processings; invalid deliveries with no payment attribution use `unknown` |
| `settlements.latency` | `settlements_latency_seconds_count/_sum/_max{bank="..."}` | Timer | `bank=bank-a \| bank-b \| cardnet` | Submission-to-terminal round trip, recorded once when a settled or failed guarded payment update succeeds |
| `settlement.webhooks.received` | `settlement_webhooks_received_total{result="..."}` | Counter | `result=accepted \| duplicate \| unauthorized \| rejected` | Once per webhook request after its receiver decision |
| `settlements.recovered` | `settlements_recovered_total` | Counter | none | Once when the recovery sweeper synthesizes a missing sequence-1 settlement into the inbox |
| `recovery.sweeps` | `recovery_sweeps_total{result="..."}` | Counter | `result=recovered \| resubmitted \| noop` | Once per stale-payment recovery action after query, synthesis/resubmission, or inbox-race no-op |
| `dunning.transitions` | `dunning_transitions_total{class="..."}` | Counter | `class=retriable \| hard_fail \| dispute` | Once when an active subscription enters `past_due`, classified by its terminal reason |
| `dunning.retries` | `dunning_retries_total{outcome="..."}` | Counter | `outcome=submitted \| declined \| duplicate` | Once per dunning retry decision after its attempt row is inserted or deduplicated |
| `dunning.recoveries` | `dunning_recoveries_total` | Counter | none | Once when a settled retry returns a `past_due` subscription to `active` and clears grace |
| `dunning.cancellations` | `dunning_cancellations_total{cause="..."}` | Counter | `cause=exhausted \| grace_expired` | Once per subscription the sweeper cancels, by verdict: bounded retriable attempts exhausted, or the grace deadline passed |
| `subscriptions.past_due` | `subscriptions_past_due` | Gauge | none | Refreshed every 10 s from the current count of `past_due` subscriptions; every replica reports the same database-global count, so the gauge is **not additive across replicas** |

All counter series are registered eagerly and therefore render as `0.0` from boot;
`verify.sh` depends on that property. Settlement outcome×bank pairs and each
counterparty's latency timer are likewise registered at startup, and all three
dunning-class counters plus both cancellation-cause counters exist before the
first transition. The renewal outcome
taxonomy is bounded to
`succeeded`, `failed`, `invalid`, and `submitted`, crossed with the bounded
`card`, `sdd`, and `unknown` method dimension. Transient or unexpected failures
increment no outcome counter because they have no decided business outcome;
retries remain visible through the listener timer's `result="failure"` tag.

| Micrometer meter | Prometheus series | Tags |
|---|---|---|
| `spring.batch.job` | `spring_batch_job_seconds_count/_sum/_max` | `spring_batch_job_name`, `spring_batch_job_status`, `error` |
| `spring.batch.step` | `spring_batch_step_seconds_count/_sum/_max` | `spring_batch_step_name`, `spring_batch_step_job_name`, `spring_batch_step_status`, `error` |
| `spring.rabbitmq.listener` | `spring_rabbitmq_listener_seconds_count/_sum/_max` | `listener_id="renewal" \| "settlement"`, `queue`, `result`, `exception` |

These timers come from Spring Batch observation support, auto-wired through
`@EnableBatchProcessing`'s `BatchObservabilityBeanPostProcessor`, and Spring AMQP's
`MicrometerHolder`; they are deliberately not hand-rolled. The `error` tag on the
batch series is added by Spring Boot's observation handler, not by Batch itself;
tag sets above match the live `/actuator/prometheus` output. The end-to-end verifier
cross-checks same-run metric deltas against database deltas because counters reset with
the service process while the database persists.

Since [R21](roadmap.md#r21) ([D12](decisions.md#d12)) the compose stack ships
its own visualization: Prometheus (`prom/prometheus:v3.5.0`, 5s scrape) reads
both services' `/actuator/prometheus`, discovers every scaled consumer replica
via DNS A-record service discovery, and reads the broker plugin's per-queue
family (`rabbitmq_detailed_queue_messages` for the renewals *and* settlements
main queues and DLQs, from `/metrics/detailed?family=queue_coarse_metrics`); Grafana
(`grafana/grafana:13.1.1` — the same app version the external platform repo
runs) provisions its datasource and the `payfold-pipeline` dashboard from
`observability/` at boot and serves it to anonymous viewers, so a fresh
`docker compose up` renders the pipeline with zero clicks. `verify.sh` asserts
Prometheus is healthy and scraping, that the dashboard is provisioned, and —
since [R33](roadmap.md#r33) — that every panel query extracted from the
provisioned JSON is accepted by live Prometheus and returns at least one series
after the run's load, so a renamed metric or broken PromQL edit fails
verification instead of leaving a lying panel behind a green run. The
dashboards are demo-local; the platform repo runs its own kube-prometheus-stack
in-cluster.

## Message contract — renewal.requested v1

The producer writes all contract fields into the outbox payload in the same scan
transaction that creates the renewal intent ([D8](decisions.md#d8)).

| Field | JSON type | Source (producer SQL) | Semantics |
|---|---|---|---|
| `schema_version` | number | literal `1` | Contract version. |
| `event_id` | string | one `gen_random_uuid()` value generated in the event CTE | Event identity; exactly equal to the containing outbox row `id`. |
| `subscription_id` | string | `subscription.id` | Subscription being renewed. |
| `customer_id` | string | `subscription.customer_id` | Customer billed by the renewal. |
| `plan_id` | string | `subscription.plan_id` | Plan snapshot identity at scan time. |
| `interval` | string | `plan.interval` | Plan interval snapshot (`month` or `year`). |
| `amount_cents` | number | `plan.price_cents` | Amount to bill in integer minor units. |
| `currency` | string | `plan.currency` | Currency code paired with `amount_cents`. |
| `idempotency_key` | string | subscription id plus formatted due date | Stable payment key: `sub-<subscription_id>\|<due_date>`. |
| `due_date` | string | local `due_ts`, cast to date and ISO-formatted | Actual charge due date (`YYYY-MM-DD`). |
| `period_start` | string | `due_date` | Billed period start; equal to `due_date`. |
| `period_end` | string | `due_date` plus `plan.interval` | Billed period end: one month or one year after `due_date`. |
| `occurred_at` | string | scan transaction `now()`, formatted in UTC | ISO-8601 event creation timestamp with zone information. |

Per [G8](invariants.md#g8), changes within v1 are additive only. Consumers tolerate
unknown fields explicitly through `@JsonIgnoreProperties(ignoreUnknown = true)` (and
Spring Boot's default ObjectMapper behavior). Removing or re-typing a field requires a
version bump and a decision entry; see [D8](decisions.md#d8).

### Message contract — settlement.received v1

The webhook relay normalizes bank callbacks into this internal, bank-agnostic
contract. `notification_id` is the settlement idempotency key, stable across
bank and broker redelivery.

| Field | JSON type | Semantics |
|---|---|---|
| `schema_version` | number | Literal `1`; contract version. |
| `notification_id` | string | Stable bank notification identity and idempotency key. |
| `bank_id` | string | Verified source bank identity from the webhook path. |
| `collection_id` | string | Submitted payment collection identity. |
| `outcome` | string | `settled`, `failed`, or `charged_back`. |
| `reason` | string or null | ISO outcome reason when supplied (for example `AM04` or `MD06`). |
| `occurred_at` | string | Bank-supplied ISO-8601 occurrence time. |

Per [G8](invariants.md#g8), settlement v1 changes are additive only and its
consumer explicitly tolerates unknown fields. Removing or re-typing a field
requires a version bump and decision entry.

## Data model (Flyway, `db-migrations/`)

| Migration | Contents |
|---|---|
| V1 | `customer`, `mandate`, `payment_method`, `plan`, `subscription`, `invoice`, `charge`, `payment` — plus `bank_tx`, `recon_match`, `ledger_entry`, which **no code touches**; reconciliation/ledger is a non-goal until promoted ([roadmap](roadmap.md#non-goals)) |
| V2 | 3 `plan` rows (Basic/Standard/Premium, cents + EUR, monthly) |
| V3 | `renewal_outbox` + the unique constraints in the table above + supporting indexes |
| V4 | Spring Batch 5 metadata schema (producer sets `spring.batch.jdbc.initialize-schema: never`; Flyway is the sole schema authority, [G3](invariants.md#g3)) |
| V5 | one yearly `plan` row ('Premium Annual') so due-today seeding has a valid renewal preimage on month-end clamp days ([R16](roadmap.md#r16)); weighted 0 in the seeder — used only via the clamp fallback |
| V6 | customer payment method plus SDD debtor material; payment bank and collection attribution for submitted collections (the in-file "NULL for card payments" comment is pre-[R23f](roadmap.md#r23f) history — cards carry bank/collection attribution since the async spine; applied migrations are immutable, [G3](invariants.md#g3)) |
| V7 | durable `settlement_inbox` with unique bank/notification identity and confirm-gated `published_at`; `payment.failure_reason` for terminal ISO outcomes |
| V8 | `payment.charged_back_at`, separating the dispute timestamp from settlement completion |
| V9 | tokenized card reference on `customer`, backfilled for legacy cards and required for every card customer |
| V10 | `subscription.grace_until` plus the partial index supporting current `past_due` depth polling |
| V11 | `payment.attempt` plus the unique `(charge_id, attempt)` key that makes retry insertion race-safe |

`renewal_outbox`: `id, subscription_id, due_date, payload jsonb, created_at, published_at`.
Unpublished = `published_at IS NULL`.

## Configuration truth table

Every runtime configuration key below has a real consumer.

| Configuration key | Consumed by | Status |
|---|---|---|
| `spring.application.name` (both) | Spring Boot application identity | alive |
| `spring.datasource.*` | Spring Boot autoconfig (overridden by compose `SPRING_DATASOURCE_*`) | alive (placeholder values in yaml) |
| `spring.jackson.time-zone` (both) | Spring Boot Jackson autoconfig | alive |
| `spring.batch.jdbc.initialize-schema` (producer) | Spring Batch | alive |
| `spring.rabbitmq.publisher-confirm-type` (producer) | Spring Boot AMQP autoconfig (`CachingConnectionFactory` confirm type); load-bearing: without it confirm futures never complete and every page times out | alive |
| `spring.rabbitmq.publisher-confirm-type` (consumer) | Spring Boot AMQP autoconfig (`CachingConnectionFactory` confirm type); load-bearing: the inbox relay gates `published_at` on correlated broker confirms | alive |
| `spring.rabbitmq.cache.channel.size` (consumer) | Spring Boot AMQP autoconfig (`CachingConnectionFactory` channel cache size); kept equal to `relay.in-flight-limit` so parked confirm channels re-cache and are reused ([D24](decisions.md#d24)) | alive |
| `spring.rabbitmq.publisher-returns` (producer) | Spring Boot AMQP autoconfig (`CachingConnectionFactory` returns support); load-bearing: without it the broker's `basic.return` is never delivered and an unroutable message is silently confirm-acked | alive |
| `spring.rabbitmq.template.mandatory` (producer) | Spring Boot AMQP autoconfig (`RabbitTemplate` mandatory flag); makes the broker return unroutable messages instead of dropping them | alive |
| `spring.rabbitmq.cache.channel.size` (producer) | Spring Boot AMQP autoconfig (`CachingConnectionFactory` channel cache size); kept equal to `app.publishInFlightLimit` so parked confirm channels re-cache and are reused ([R25](roadmap.md#r25)) | alive |
| `app.timezone`, `app.scheduleCron`, `app.scanPageSize`, `app.publishPageSize`, `app.publishInFlightLimit`, `app.confirmTimeoutMs` (producer) | `RenewalScheduler`, `RenewalJobConfig`, `RenewalJobEndpoint` | alive |
| `rabbitmq.exchange`, `rabbitmq.routingKey` (producer) | `RabbitConfig`, `OutboxPublisher` | alive |
| `rabbitmq.exchange/queue/routingKey` (consumer) | `RabbitTopology`, `RenewalListener` | alive |
| `relay.page-size`, `relay.in-flight-limit`, `relay.confirm-timeout-ms` (consumer) | `RelayProperties`, `SettlementInboxRelay` — page claim size, bounded in-flight confirm window, page-scoped send+confirm deadline ([D24](decisions.md#d24)); defaults 500 / 100 / 5000 ms, env-overridable via relaxed binding (`RELAY_PAGE_SIZE`, `RELAY_IN_FLIGHT_LIMIT`, `RELAY_CONFIRM_TIMEOUT_MS`) | alive |
| `bank.timeout-ms` (consumer) | `BankProperties`, `BankClient` connect + read timeout; 10 s — polices hung counterparties, while overload backpressure comes from blocked listener threads ([D19](decisions.md#d19)) | alive |
| `bank.registry[]` id/scheme/base URL/webhook secret/countries (consumer) | `BankProperties`, `BankRegistry`, `BankClient`, `BillingService`, `BankWebhookController`; `countries` is required only for `sepa_core`, and the registry requires exactly one `card` entry; compose overrides indexed `BANK_REGISTRY_*` env vars | alive |
| `RECOVERY_STALE_AFTER_SECONDS` (consumer; yaml `recovery.stale-after-seconds`) | `RecoveryProperties`, `RecoverySweeper`; compose overrides the 300 s application default with 30 s for verify/demo | alive |
| `RECOVERY_SWEEP_INTERVAL_MS` (consumer; yaml `recovery.sweep-interval-ms`) | `RecoverySweeper`'s `@Scheduled` placeholders (fixed delay *and* initial delay — the first sweep fires one interval after startup, not at boot); compose overrides the 60000 ms application default with 10000 ms for verify/demo | alive |
| `dunning.classes.*` (consumer; yaml only) | `DunningProperties`, `DunningLifecycle`, `DunningSweeper` (retriable-reason picker); fixed reason-to-class policy, deliberately not env-overridable | alive |
| `DUNNING_RETRIABLE_GRACE_SECONDS` (consumer; yaml `dunning.retriable-grace-seconds`) | `DunningProperties`, `DunningLifecycle`; compose overrides the 604800 s application default with 180 s for verify/demo (bounded exhaustion demonstrably beats the expiry backstop at the compose cadence — [D21](decisions.md#d21), margin re-widened by [D23](decisions.md#d23) after the restored fast drain clustered first-failures and 6/350 stragglers fell to the backstop) | alive |
| `DUNNING_HARD_FAIL_GRACE_SECONDS` (consumer; yaml `dunning.hard-fail-grace-seconds`) | `DunningProperties`, `DunningLifecycle`; compose overrides the 259200 s application default with 60 s for verify/demo | alive |
| `DUNNING_DISPUTE_GRACE_SECONDS` (consumer; yaml `dunning.dispute-grace-seconds`) | `DunningProperties`, `DunningLifecycle`; compose overrides the 1209600 s application default with 60 s for verify/demo | alive |
| `DUNNING_RETRY_DELAY_SECONDS` (consumer; yaml `dunning.retry-delay-seconds`) | `DunningProperties`, `DunningSweeper`; compose overrides the 86400 s application default with 15 s for verify/demo | alive |
| `DUNNING_MAX_ATTEMPTS` (consumer; yaml `dunning.max-attempts`) | `DunningProperties`, `DunningSweeper` (which passes the bound into `DunningLifecycle.cancelExhausted`); total attempts (base + re-collections) before the sweeper cancels as exhausted; compose overrides the 4 application default with 3 for verify/demo | alive |
| `DUNNING_SWEEP_INTERVAL_MS` (consumer; yaml `dunning.sweep-interval-ms`) | `DunningSweeper`'s `@Scheduled` placeholders (fixed delay *and* initial delay — the first sweep fires one interval after startup, not at boot); compose overrides the 60000 ms application default with 10000 ms for verify/demo | alive |
| `SEED_CUSTOMERS` (seeder) | `CustomerSeeder`; compose-only seed size (default 15000) — every seeded subscription is due on the seed day, so this sets the size of the day's renewal batch | alive |
| `SEED_SDD_PERCENT` (seeder) | `CustomerSeeder`; compose-only share of customers paying by SDD (deterministic: customer number modulo 100; default 20) | alive |
| `SEED_SDD_RULE_PERCENT` (seeder) | `CustomerSeeder`; compose-only rule-bearing IBAN-suffix share inside the SDD cohort (cycling AM04/AC04/MD01/MD06; default 4) | alive |
| `SEED_CARD_RULE_PERCENT` (seeder) | `CustomerSeeder`; compose-only rule-bearing card-token share inside the card cohort (cycling decline/chargeback cases; default 4) | alive |
| `SEED_SDD_SILENT_PERCENT` (seeder) | `CustomerSeeder`; compose-only deterministic suffix-94 share inside the SDD cohort | alive |
| `SEED_CARD_SILENT_PERCENT` (seeder) | `CustomerSeeder`; compose-only deterministic suffix-94 share inside the card cohort | alive |
| `SEED_SDD_RETRY_PERCENT` (seeder) | `CustomerSeeder`; compose-only deterministic suffix-95 share inside the SDD cohort, mirroring the silent slice | alive |
| `SEED_CARD_RETRY_PERCENT` (seeder) | `CustomerSeeder`; compose-only deterministic suffix-95 share inside the card cohort, mirroring the silent slice | alive |
| `spring.rabbitmq.listener.simple.*` (consumer) | Spring Boot AMQP autoconfig + `ListenerRetryConfig` (`max-attempts`) | alive |
| `management.endpoints.web.exposure.include` (producer) | actuator exposure for `health`, `info`, `metrics`, `prometheus`, and `renewal-job` | alive |
| `management.endpoints.web.exposure.include` (consumer) | actuator exposure for `health`, `info`, `metrics`, and `prometheus`; the compose healthcheck relies on `health` | alive |
| `management.endpoint.health.show-details` (producer) | actuator health response detail policy | alive |

[R2](roadmap.md#r2) replaced the producer's dotted app-specific environment names with
Spring relaxed-binding underscore names and added named-volume defaults for Postgres
and RabbitMQ; local `.env` values can still select bind-mount paths. The consumer's
compose healthcheck hits `/actuator/health`, served by actuator since
[R1](roadmap.md#r1).

All eight `SEED_*` keys are compose-only and carried as truth-table rows above
(the silent and retry shares default to 2% per cohort). Together they
deterministically partition customers and assign rule-bearing, silent, or
recoverable IBAN/card-token suffixes from the global customer number;
`scripts/load-test.sh` adds more due-today volume to a running stack without a
reseed.

The mock-counterparty instances are likewise configured entirely by compose-only
envs: bank-a uses the `BANK_*` variables, bank-b uses the corresponding
`BANK_B_*` values, and cardnet uses `CARDNET_*`; each container receives its own
`BANK_ID`, `BANK_SCHEME`,
`BANK_WEBHOOK_URL`, `BANK_WEBHOOK_SECRET`,
`BANK_SETTLEMENT_DELAY_SECONDS`, and `BANK_CHARGEBACK_LAG_SECONDS`, and cardnet
additionally raises `BANK_WORKERS` via `CARDNET_WORKERS` (default 4,
[D19](decisions.md#d19)). All three
share the compose-set delivery envelope `BANK_WEBHOOK_RETRY_MAX_ATTEMPTS` /
`BANK_WEBHOOK_RETRY_BACKOFF_SECONDS` (default 8 attempts, 2 s base — wider than
the image defaults, sized to outlive a chaos-scene consumer outage; the
recovery story for exhausted deliveries is [R28](roadmap.md#r28)).

`CONSUMER_LISTENER_CONCURRENCY` (default 1) passes straight
through to `spring.rabbitmq.listener.simple.concurrency`, and the consumer's host ports
are the range `CONSUMER_HTTP_PORT`–`CONSUMER_HTTP_PORT_END` (defaults
8081–8083) so `docker compose up --scale renewal-consumer=N` can bind every
replica; `verify.sh` sums the per-replica counters over that range
([R20](roadmap.md#r20)).
On month-end clamp days (Jul 31, Dec 31, …) every seeding path falls back to the
V5 yearly plan, whose one-year preimage exists on all such days; the monthly path
covers Feb 29, where only the one-month preimage exists.
The deploy images' env contracts (`FLYWAY_*` and the seeder's
`POSTGRES_URL`/`POSTGRES_USER`/`POSTGRES_PASSWORD`) are catalogued under
[Deploy artifacts](#deploy-artifacts); the compose stack's own plumbing
variables are the table below.

### Stack plumbing (compose-only)

Infrastructure wiring consumed by `docker-compose.yaml` itself (defaults from
`.env.example`), not by application code — listed because the truth table is
the documented coupling surface and these are the knobs a local override
actually turns:

| Variable | Default | Purpose |
|---|---|---|
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | `admin` / `admin` | Database credentials, fed to Postgres, Flyway, the seeder, and both services' datasources |
| `POSTGRES_DB` | `payfold` | Database name in every JDBC/Flyway URL |
| `POSTGRES_PORT` | `5432` | Postgres host port |
| `POSTGRES_VOLUME` | `pg_data` | Postgres data volume — named volume by default, a bind path if overridden |
| `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | `guest` / `guest` | Broker credentials, fed to the broker and both services |
| `RABBITMQ_PORT` / `RABBITMQ_MGMT_PORT` | `5672` / `15672` | AMQP and management-API host ports |
| `RABBITMQ_VOLUME` | `rmq_data` | Broker data volume |
| `PRODUCER_HTTP_PORT` | `8080` | Producer host port (the consumer's range is documented above) |
| `BANK_HTTP_PORT` / `BANK_B_HTTP_PORT` / `CARDNET_HTTP_PORT` | `8085` / `8086` / `8087` | Counterparty host ports |
| `PROMETHEUS_PORT` / `GRAFANA_PORT` | `9090` / `3000` | Observability host ports |
| `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD` | `admin` / `payfold` | Mapped to Grafana's `GF_SECURITY_ADMIN_*`; anonymous viewer access is fixed in compose via `GF_AUTH_ANONYMOUS_ENABLED` + `GF_AUTH_ANONYMOUS_ORG_ROLE` |

## Ports & endpoints

| Where | What |
|---|---|
| `localhost:8080` | producer — `/actuator/health`, `/actuator/prometheus`, `POST /actuator/renewal-job?force=true`, `GET /actuator/renewal-job/{executionId}` |
| `localhost:8081` | consumer's first replica — `/actuator/health` (since [R1](roadmap.md#r1)), `/actuator/prometheus`, `POST /webhooks/bank/{bankId}`; scaled replicas bind 8082–8083 with the same endpoints; container-internal 8080 |
| `localhost:8085` | mock bank-a (FastAPI, BE+FR fast profile) — `POST /collections`, `GET /collections/{id}`, `/health`, `/metrics` |
| `localhost:8086` | mock bank-b (same image, NL+IE slow profile) — `POST /collections`, `GET /collections/{id}`, `/health`, `/metrics` |
| `localhost:8087` | mock cardnet (same FastAPI image, card scheme) — sync auth from `POST /collections`, then async settlement; `/health`, `/metrics` |
| `localhost:9090` | Prometheus — targets, `/api/v1/query`, `/-/healthy` |
| `localhost:3000` | Grafana — `payfold-pipeline` dashboard, anonymous viewer access |
| `localhost:5672` / `15672` | RabbitMQ AMQP / management UI (creds from `.env`) |
| `localhost:5432` | Postgres (creds from `.env`) |

## Deploy artifacts (published images)

<a id="deploy-artifacts"></a>
Per [D11](decisions.md#d11)/[R18](roadmap.md#r18), a manually pushed `vX.Y.Z` git
tag runs `.github/workflows/publish.yml`, which publishes five images to GHCR.
Tags are immutable semver — never `latest`, never a mutable tag: the external
platform repo pins exact tags in Git, and ordered semver is what lets its image
automation bump them commit-by-commit. Every tag is a linux/amd64 + linux/arm64
manifest list (buildx; check with `docker manifest inspect` — BuildKit's
`unknown/unknown` attestation entries are expected). The service build stages are
pinned to `$BUILDPLATFORM`, so a multi-arch build compiles the
architecture-independent jar once instead of emulating Maven under QEMU.

| Image (`ghcr.io/diblan/…`) | Contents | Run pattern | Config (env) |
|---|---|---|---|
| `payfold-renewal-producer` | producer Spring Boot jar | long-running service; port 8080, `/actuator/health` | the compose `renewal-producer` env block: `SPRING_DATASOURCE_*`, `SPRING_RABBITMQ_*`, `RABBITMQ_EXCHANGE`, `RABBITMQ_ROUTINGKEY`, `APP_TIMEZONE`, `APP_SCHEDULECRON`, `TZ` |
| `payfold-renewal-consumer` | consumer Spring Boot jar | long-running service; port 8080 (host 8081 in compose), `/actuator/health` | the compose `renewal-consumer` env block: `SPRING_DATASOURCE_*`, `SPRING_RABBITMQ_*`, `RABBITMQ_EXCHANGE`, `RABBITMQ_QUEUE`, `RABBITMQ_ROUTINGKEY`, indexed `BANK_REGISTRY_*` including scheme, `RECOVERY_STALE_AFTER_SECONDS`, `RECOVERY_SWEEP_INTERVAL_MS`, the three `DUNNING_*_GRACE_SECONDS`, `DUNNING_RETRY_DELAY_SECONDS`, `DUNNING_MAX_ATTEMPTS`, `DUNNING_SWEEP_INTERVAL_MS`, `TZ` |
| `payfold-migrations` | `flyway/flyway:11` + `db-migrations/V*.sql`, `CMD ["migrate"]` | run-to-completion Job; exit 0 = success; re-run on a current schema is a no-op (asserted by `verify.sh`) | `FLYWAY_URL`, `FLYWAY_USER`, `FLYWAY_PASSWORD`, `FLYWAY_CONNECT_RETRIES` (image default 30) |
| `payfold-seed-data-gen` | seeder source + PostgreSQL JDBC driver + name data; compiles at container start | run-to-completion Job; exit 0 = success; needs a writable `SEED_OUT_DIR` (default `/tmp/seed-out`) | `POSTGRES_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `SEED_CUSTOMERS`, `SEED_SDD_PERCENT`, `SEED_SDD_RULE_PERCENT`, `SEED_SDD_SILENT_PERCENT`, `SEED_SDD_RETRY_PERCENT`, `SEED_CARD_RULE_PERCENT`, `SEED_CARD_SILENT_PERCENT`, `SEED_CARD_RETRY_PERCENT` |
| `payfold-mock-bank` | FastAPI mock counterparty (source + pinned pure-python deps) | long-running service; port 8080, `/health`; compose runs two SEPA instances and one card instance | `BANK_ID`, `BANK_SCHEME`, `BANK_WORKERS`, `BANK_WEBHOOK_URL`, `BANK_WEBHOOK_SECRET`, `BANK_SETTLEMENT_DELAY_SECONDS`, `BANK_CHARGEBACK_LAG_SECONDS`, `BANK_WEBHOOK_RETRY_MAX_ATTEMPTS`, `BANK_WEBHOOK_RETRY_BACKOFF_SECONDS`, `TZ` |

Compose builds `payfold-migrations` and `payfold-seed-data-gen` itself (the flyway
and seed-data services) instead of bind-mounting host paths, so the local stack
and `verify.sh` exercise the same artifact shape a cluster runs. The coupling
surface the platform repo consumes is exactly: these images, the
[configuration truth table](#configuration-truth-table), ports 8080/8081, and
`/actuator/health` (readiness) and `/actuator/health/liveness` (liveness) — a
change to any of them must be flagged loudly, and R18 changes nothing in the
truth table itself. GHCR packages created by an Actions workflow via
`GITHUB_TOKEN` are linked to the repository and inherit its visibility — payfold
is public, so its packages publish public (observed at `v0.1.0`); a private
repo's packages would need a visibility change or a pull secret.
Spring Boot auto-enables the liveness/readiness probe groups when it detects
Kubernetes, so `/actuator/health/liveness` exists in-cluster without extra
configuration; the platform's liveness probes consume it (app-internal state
only — an infrastructure outage degrades readiness, never liveness).
