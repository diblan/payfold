#!/usr/bin/env bash
# End-to-end verification for the Payfold stack. Exit 0 = the system works.
#
# This script is the machine-checkable definition of "working" (see AGENTS.md and
# docs/invariants.md G7): it may only ever be made stricter, never loosened.
# It includes exact deterministic provider-failure assertions derived from the
# PSP_FAIL_HEX environment variable and same-run Prometheus metric/DB delta
# cross-checks.
#
# Usage:
#   scripts/verify.sh [--no-up] [--timeout SECONDS] [--poison|--no-poison]
#
#   --no-up        skip `docker compose up -d --build` (stack already running)
#   --timeout N    max seconds to wait for each long condition (default 600)
#   --poison       run the poison-message DLQ probe (default since R5)
#   --no-poison    skip poison payload/DLQ checks only; listener metrics stay required
#
# Environment:
#   VERIFY_STRICT_CONSUMER_HEALTH=1   require the consumer's /actuator/health to be UP.
#     Default 1 since roadmap item R1 (consumer ships actuator) — a tightening, per
#     G7. Set 0 only to debug a stack whose consumer is known-broken.
#   The R5 poison probe is also strict by default: management API failures fail
#   verification, and poison must reach the DLQ within its independent 60s cap.
#
# Requires: docker compose v2, curl. psql runs inside the postgres container.
# Safe to re-run against a dirty database: assertions are absolute conditions plus
# same-run metric deltas whose baselines are snapshotted within this run, so process
# restarts and persisted database rows do not skew the cross-checks.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

TIMEOUT=600
NO_UP=0
POISON=1
while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-up) NO_UP=1; shift ;;
    --timeout) TIMEOUT="${2:?--timeout needs a value}"; shift 2 ;;
    --poison) POISON=1; shift ;;
    --no-poison) POISON=0; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

STRICT_CONSUMER="${VERIFY_STRICT_CONSUMER_HEALTH:-1}"

# .env cannot be `source`d (values contain unquoted spaces/#); grep the keys we need.
env_val() {
  local v=""
  if [[ -f .env ]]; then
    v="$(grep -E "^$1=" .env | head -1 | cut -d= -f2- | sed 's/[[:space:]]*$//')"
  fi
  printf '%s' "${v:-$2}"
}

PGUSER="$(env_val POSTGRES_USER admin)"
PGDB="$(env_val POSTGRES_DB payfold)"
PRODUCER_PORT="$(env_val PRODUCER_HTTP_PORT 8080)"
CONSUMER_PORT="$(env_val CONSUMER_HTTP_PORT 8081)"
RMQ_USER="$(env_val RABBITMQ_USER guest)"
RMQ_PASS="$(env_val RABBITMQ_PASSWORD guest)"
RMQ_MGMT_PORT="$(env_val RABBITMQ_MGMT_PORT 15672)"
RMQ_QUEUE="$(env_val RABBITMQ_QUEUE billing.renewals.main)"
RMQ_EXCHANGE="$(env_val RABBITMQ_EXCHANGE billing.renewals)"
RMQ_RK="$(env_val RABBITMQ_ROUTINGKEY renewal.requested)"
PSP_FAIL_HEX="$(env_val PSP_FAIL_HEX 0)"
RMQ_DLQ="billing.renewals.dlq"

RESULTS=()
FAIL_COUNT=0
pass() { RESULTS+=("PASS  $1"); echo "[verify] PASS  $1"; }
fail() { RESULTS+=("FAIL  $1${2:+ — $2}"); echo "[verify] FAIL  $1${2:+ — $2}" >&2; ((FAIL_COUNT++)); }
warn() { RESULTS+=("WARN  $1${2:+ — $2}"); echo "[verify] WARN  $1${2:+ — $2}"; }
note() { echo "[verify] $*"; }

q() { docker compose exec -T postgres psql -U "$PGUSER" -d "$PGDB" -Atc "$1" 2>/dev/null; }

# wait_for <description> <function> — polls every 2s up to $TIMEOUT
wait_for() {
  local desc="$1" cond="$2" start=$SECONDS
  while (( SECONDS - start < TIMEOUT )); do
    if "$cond"; then pass "$desc"; return 0; fi
    sleep 2
  done
  fail "$desc" "timed out after ${TIMEOUT}s"
  return 1
}

summary() {
  echo
  echo "================ verify.sh summary ================"
  printf '%s\n' "${RESULTS[@]}"
  echo "==================================================="
  if (( FAIL_COUNT > 0 )); then
    echo "RESULT: FAIL (${FAIL_COUNT} failed)"
    exit 1
  fi
  echo "RESULT: PASS"
  exit 0
}

# --- conditions -------------------------------------------------------------

pg_ready()   { [[ "$(q 'SELECT 1')" == "1" ]]; }
seeded()     { [[ "$(q 'SELECT count(*) FROM customer')" -gt 0 && "$(q 'SELECT count(*) FROM subscription')" -gt 0 ]] 2>/dev/null; }
producer_up() { curl -fsS "http://localhost:${PRODUCER_PORT}/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; }
consumer_up() { curl -fsS "http://localhost:${CONSUMER_PORT}/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; }
consumer_running() { docker compose ps --status running --services 2>/dev/null | grep -qx renewal-consumer; }

outbox_drained() { [[ "$(q 'SELECT count(*) FROM renewal_outbox WHERE published_at IS NULL')" == "0" ]]; }

# A today-due outbox row is correctly billed when a payment exists under its
# derived idempotency key with EXACTLY the terminal status the deterministic
# mock-PSP rule predicts: 'failed' when the subscription id's last hex char is
# in [$PSP_FAIL_HEX], 'succeeded' otherwise.
MISMATCHED_SQL="SELECT count(*) FROM renewal_outbox o
WHERE o.due_date = current_date
  AND NOT EXISTS (
    SELECT 1 FROM payment p
    WHERE p.idempotency_key = 'sub-' || o.subscription_id || '|' || to_char(current_date, 'YYYY-MM-DD')
      AND p.status = CASE WHEN right(o.subscription_id::text, 1) ~ '[${PSP_FAIL_HEX}]'
                          THEN 'failed' ELSE 'succeeded' END
  )"
all_billed() { [[ "$(q "$MISMATCHED_SQL")" == "0" ]]; }

queue_depth() { # queue name -> message count, or "unreachable"
  local body
  body="$(curl -fsS -u "${RMQ_USER}:${RMQ_PASS}" \
    "http://localhost:${RMQ_MGMT_PORT}/api/queues/%2F/$1" 2>/dev/null)" || { echo unreachable; return; }
  echo "$body" | grep -o '"messages":[0-9]*' | head -1 | cut -d: -f2
}

prom_val() { # port, extended-regex over metric lines -> integer sum | absent | unreachable
  local body
  body="$(curl -fsS "http://localhost:$1/actuator/prometheus" 2>/dev/null)" || { echo unreachable; return; }
  echo "$body" | grep -E "$2" \
    | awk '{s+=$NF} END { if (NR==0) print "absent"; else printf "%.0f\n", s }'
}

producer_prometheus_ready() {
  local inserted published
  inserted="$(prom_val "$PRODUCER_PORT" '^outbox_inserted_total ')"
  published="$(prom_val "$PRODUCER_PORT" '^outbox_published_total ')"
  [[ "$inserted" != "absent" && "$inserted" != "unreachable"
    && "$published" != "absent" && "$published" != "unreachable" ]]
}
consumer_prometheus_ready() {
  local processed
  processed="$(prom_val "$CONSUMER_PORT" '^renewals_processed_total\{outcome="(succeeded|failed)"\}')"
  [[ "$processed" != "absent" && "$processed" != "unreachable" ]]
}
main_queue_empty() { [[ "$(queue_depth "$RMQ_QUEUE")" == "0" ]]; }

trigger_job() { # -> echoes HTTP status code ("000" on timeout/conn error)
  curl -sS -o /dev/null -w '%{http_code}' --max-time 240 \
    -X POST "http://localhost:${PRODUCER_PORT}/actuator/renewal-job?force=true" \
    -H 'Content-Type: application/vnd.spring-boot.actuator.v3+json' \
    -H 'Accept: application/json' -d '{}' 2>/dev/null
}

# --- run --------------------------------------------------------------------

if (( ! NO_UP )); then
  note "starting stack (docker compose up -d --build)…"
  if docker compose up -d --build; then
    pass "stack started"
  else
    fail "stack started" "docker compose up failed"
    summary
  fi
fi

wait_for "postgres reachable"        pg_ready      || summary
wait_for "seed data present"         seeded        || summary
wait_for "producer /actuator/health UP" producer_up || summary

if [[ "$STRICT_CONSUMER" == "1" ]]; then
  wait_for "consumer /actuator/health UP" consumer_up
else
  if consumer_running; then
    pass "consumer container running (strict health check explicitly disabled)"
  else
    fail "consumer container running"
  fi
fi

wait_for "producer /actuator/prometheus serves outbox counters" producer_prometheus_ready || summary
wait_for "consumer /actuator/prometheus serves renewals counter" consumer_prometheus_ready || summary

M_INS_BEFORE="$(prom_val "$PRODUCER_PORT" '^outbox_inserted_total ')"
M_PUB_BEFORE="$(prom_val "$PRODUCER_PORT" '^outbox_published_total ')"
M_PROC_BEFORE="$(prom_val "$CONSUMER_PORT" '^renewals_processed_total\{outcome="(succeeded|failed)"\}')"
DB_OUTBOX_BEFORE="$(q 'SELECT count(*) FROM renewal_outbox')"
DB_PUB_BEFORE="$(q 'SELECT count(*) FROM renewal_outbox WHERE published_at IS NOT NULL')"

note "triggering renewal job (synchronous endpoint — may take a while)…"
HTTP_CODE="$(trigger_job)"
if [[ "$HTTP_CODE" == "200" ]]; then
  pass "renewal job trigger returned 200"
elif [[ "$HTTP_CODE" == "000" ]]; then
  warn "renewal job trigger" "no response within 240s (endpoint blocks until job completes — R10); relying on DB polling"
else
  fail "renewal job trigger" "HTTP ${HTTP_CODE}"
fi

N_DUE="$(q 'SELECT count(*) FROM renewal_outbox WHERE due_date = current_date')"
if [[ -n "$N_DUE" && "$N_DUE" -gt 0 ]]; then
  pass "outbox contains renewals due today (${N_DUE})"
else
  fail "outbox contains renewals due today" "count=${N_DUE:-error}"
fi

wait_for "outbox fully published"                outbox_drained
wait_for "every due renewal reached its predicted terminal payment (PSP rule [${PSP_FAIL_HEX}])" all_billed

DB_OUTBOX_AFTER="$(q 'SELECT count(*) FROM renewal_outbox')"
DB_PUB_AFTER="$(q 'SELECT count(*) FROM renewal_outbox WHERE published_at IS NOT NULL')"
DB_INS_DELTA=unavailable
DB_PUB_DELTA=unavailable
if [[ "$DB_OUTBOX_BEFORE" =~ ^[0-9]+$ && "$DB_OUTBOX_AFTER" =~ ^[0-9]+$ ]]; then
  DB_INS_DELTA=$((DB_OUTBOX_AFTER - DB_OUTBOX_BEFORE))
fi
if [[ "$DB_PUB_BEFORE" =~ ^[0-9]+$ && "$DB_PUB_AFTER" =~ ^[0-9]+$ ]]; then
  DB_PUB_DELTA=$((DB_PUB_AFTER - DB_PUB_BEFORE))
fi

inserted_metric_delta_matches() {
  local current
  current="$(prom_val "$PRODUCER_PORT" '^outbox_inserted_total ')"
  [[ "$current" =~ ^[0-9]+$ ]] && (( current - M_INS_BEFORE == DB_INS_DELTA ))
}
published_metric_delta_matches() {
  local current
  current="$(prom_val "$PRODUCER_PORT" '^outbox_published_total ')"
  [[ "$current" =~ ^[0-9]+$ ]] && (( current - M_PUB_BEFORE == DB_PUB_DELTA ))
}
processed_metric_delta_matches() {
  local current
  current="$(prom_val "$CONSUMER_PORT" '^renewals_processed_total\{outcome="(succeeded|failed)"\}')"
  [[ "$current" =~ ^[0-9]+$ ]] && (( current - M_PROC_BEFORE == DB_PUB_DELTA ))
}
batch_job_timer_recorded() {
  local count
  count="$(prom_val "$PRODUCER_PORT" '^spring_batch_job_seconds_count')"
  [[ "$count" != "absent" && "$count" != "unreachable" ]]
}

if [[ "$M_INS_BEFORE" =~ ^[0-9]+$ && "$DB_INS_DELTA" =~ ^-?[0-9]+$ ]]; then
  wait_for "outbox_inserted_total delta matches outbox rows inserted (${DB_INS_DELTA})" inserted_metric_delta_matches
else
  fail "outbox_inserted_total delta matches outbox rows inserted (${DB_INS_DELTA})" \
    "invalid baseline=${M_INS_BEFORE} or DB delta=${DB_INS_DELTA}"
fi
if [[ "$M_PUB_BEFORE" =~ ^[0-9]+$ && "$DB_PUB_DELTA" =~ ^-?[0-9]+$ ]]; then
  wait_for "outbox_published_total delta matches rows published this run (${DB_PUB_DELTA})" published_metric_delta_matches
else
  fail "outbox_published_total delta matches rows published this run (${DB_PUB_DELTA})" \
    "invalid baseline=${M_PUB_BEFORE} or DB delta=${DB_PUB_DELTA}"
fi
if [[ "$M_PROC_BEFORE" =~ ^[0-9]+$ && "$DB_PUB_DELTA" =~ ^-?[0-9]+$ ]]; then
  wait_for "renewals_processed_total{succeeded,failed} delta matches rows published this run (${DB_PUB_DELTA})" processed_metric_delta_matches
else
  fail "renewals_processed_total{succeeded,failed} delta matches rows published this run (${DB_PUB_DELTA})" \
    "invalid baseline=${M_PROC_BEFORE} or DB delta=${DB_PUB_DELTA}"
fi
wait_for "spring_batch_job_seconds recorded on producer" batch_job_timer_recorded

EXPECTED_FAILED="$(q "SELECT count(*) FROM renewal_outbox o WHERE o.due_date = current_date AND right(o.subscription_id::text, 1) ~ '[${PSP_FAIL_HEX}]'")"
ACTUAL_FAILED="$(q "SELECT count(*) FROM payment WHERE status = 'failed' AND idempotency_key LIKE 'sub-%|' || to_char(current_date, 'YYYY-MM-DD')")"
if [[ -n "$EXPECTED_FAILED" && "$ACTUAL_FAILED" == "$EXPECTED_FAILED" ]]; then
  pass "failed payment count matches deterministic PSP rule exactly (PSP_FAIL_HEX=[${PSP_FAIL_HEX}], failed=${ACTUAL_FAILED}/${N_DUE})"
else
  fail "failed payment count matches deterministic PSP rule exactly" "expected=${EXPECTED_FAILED:-error} actual=${ACTUAL_FAILED:-error}"
fi

FAILED_FINALIZED="$(q "SELECT count(*) FROM payment p JOIN charge c ON c.id = p.charge_id WHERE p.status = 'failed' AND c.status <> 'pending'")"
if [[ "$FAILED_FINALIZED" == "0" ]]; then
  pass "no failed payment has a finalized charge"
else
  fail "no failed payment has a finalized charge" "count=${FAILED_FINALIZED:-error}"
fi

FAILED_ADVANCED="$(q "SELECT count(*) FROM renewal_outbox o JOIN subscription s ON s.id = o.subscription_id
WHERE o.due_date = current_date
  AND right(o.subscription_id::text, 1) ~ '[${PSP_FAIL_HEX}]'
  AND s.renewed_at >= current_date")"
if [[ "$FAILED_ADVANCED" == "0" ]]; then
  pass "no failed renewal advanced its subscription"
else
  fail "no failed renewal advanced its subscription" "count=${FAILED_ADVANCED:-error}"
fi

PENDING="$(q "SELECT count(*) FROM payment WHERE status = 'pending'")"
if [[ "$PENDING" == "0" ]]; then
  pass "no stuck pending payments"
else
  fail "no stuck pending payments" "count=${PENDING:-error}"
fi

MAIN_DEPTH="$(queue_depth "$RMQ_QUEUE")"
if [[ "$MAIN_DEPTH" == "unreachable" ]]; then
  warn "queue depths" "RabbitMQ management API unreachable on :${RMQ_MGMT_PORT}; skipping"
else
  if wait_for "main queue drained" main_queue_empty; then :; fi
  DLQ_DEPTH="$(queue_depth "$RMQ_DLQ")"
  if [[ "$DLQ_DEPTH" == "0" ]]; then
    pass "DLQ empty"
  else
    fail "DLQ empty" "depth=${DLQ_DEPTH}"
  fi
fi

note "same-day idempotency probe: re-triggering job, expecting zero new records…"
SNAP_SQL="SELECT (SELECT count(*) FROM renewal_outbox) || '|' || (SELECT count(*) FROM payment) || '|' || (SELECT count(*) FROM charge) || '|' || (SELECT count(*) FROM invoice)"
SNAP_BEFORE="$(q "$SNAP_SQL")"
HTTP_CODE="$(trigger_job)"
if [[ "$HTTP_CODE" != "200" && "$HTTP_CODE" != "000" ]]; then
  fail "idempotency probe re-trigger" "HTTP ${HTTP_CODE}"
fi
sleep 5   # allow any (unexpected) messages to flow through
SNAP_AFTER="$(q "$SNAP_SQL")"
if [[ -n "$SNAP_BEFORE" && "$SNAP_BEFORE" == "$SNAP_AFTER" ]]; then
  pass "same-day idempotency: outbox/payment/charge/invoice counts unchanged (${SNAP_AFTER})"
else
  fail "same-day idempotency" "before=${SNAP_BEFORE} after=${SNAP_AFTER}"
fi

if (( POISON )); then
  note "poison-message probe: publishing malformed payload and expecting bounded dead-lettering…"
  POISON_MARKER="payfold-poison-probe-$(date +%s)-$$"
  if ! PUBLISH_RESPONSE="$(curl -fsS -u "${RMQ_USER}:${RMQ_PASS}" \
    -X POST "http://localhost:${RMQ_MGMT_PORT}/api/exchanges/%2F/${RMQ_EXCHANGE}/publish" \
    -H 'Content-Type: application/json' \
    -d "{\"properties\":{},\"routing_key\":\"${RMQ_RK}\",\"payload\":\"${POISON_MARKER}\",\"payload_encoding\":\"string\"}" \
    2>/dev/null)"; then
    fail "poison message published" "RabbitMQ management API unreachable on :${RMQ_MGMT_PORT}"
    summary
  elif echo "$PUBLISH_RESPONSE" | grep -q '"routed":true'; then
    pass "poison message published and routed"
  else
    fail "poison message published and routed" "response=${PUBLISH_RESPONSE:-empty}"
    summary
  fi

  POISON_DLQ_READY=0
  POISON_WAIT_START=$SECONDS
  while (( SECONDS - POISON_WAIT_START < 60 )); do
    if [[ "$(queue_depth "$RMQ_DLQ")" == "1" ]]; then
      POISON_DLQ_READY=1
      break
    fi
    sleep 2
  done
  if (( POISON_DLQ_READY )); then
    pass "poison message reached DLQ within 60s"
  else
    fail "poison message reached DLQ within 60s" \
      "if the broker carries pre-R5 queue args, wipe the RabbitMQ volume (docker compose down -v) so the queue is redeclared"
  fi

  MAIN_DEPTH="$(queue_depth "$RMQ_QUEUE")"
  if [[ "$MAIN_DEPTH" == "0" ]]; then
    pass "main queue empty after poison message"
  else
    fail "main queue empty after poison message" "depth=${MAIN_DEPTH}"
  fi

  if ! DRAIN_RESPONSE="$(curl -fsS -u "${RMQ_USER}:${RMQ_PASS}" \
    -X POST "http://localhost:${RMQ_MGMT_PORT}/api/queues/%2F/${RMQ_DLQ}/get" \
    -H 'Content-Type: application/json' \
    -d '{"count":5,"ackmode":"ack_requeue_false","encoding":"auto"}' \
    2>/dev/null)"; then
    fail "poison message drained from DLQ" "RabbitMQ management API unreachable on :${RMQ_MGMT_PORT}"
  elif echo "$DRAIN_RESPONSE" | grep -Fq "$POISON_MARKER"; then
    pass "poison message drained from DLQ"
  else
    fail "poison message drained from DLQ" "marker not found in response"
  fi

  # The management API's message counter refreshes on a ~5s stats interval; poll
  # briefly instead of trusting the first read after the drain.
  POISON_DLQ_EMPTY=0
  POISON_EMPTY_START=$SECONDS
  while (( SECONDS - POISON_EMPTY_START < 30 )); do
    if [[ "$(queue_depth "$RMQ_DLQ")" == "0" ]]; then
      POISON_DLQ_EMPTY=1
      break
    fi
    sleep 2
  done
  if (( POISON_DLQ_EMPTY )); then
    pass "DLQ empty after poison probe"
  else
    fail "DLQ empty after poison probe" "depth=$(queue_depth "$RMQ_DLQ")"
  fi
fi

listener_timer_recorded() {
  local count
  count="$(prom_val "$CONSUMER_PORT" '^spring_rabbitmq_listener_seconds_count')"
  [[ "$count" != "absent" && "$count" != "unreachable" ]]
}
wait_for "spring_rabbitmq_listener timer recorded on consumer" listener_timer_recorded

summary
