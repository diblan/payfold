#!/usr/bin/env bash
# End-to-end verification for the Payfold stack. Exit 0 = the system works.
#
# This script is the machine-checkable definition of "working" (see AGENTS.md and
# docs/invariants.md G7): it may only ever be made stricter, never loosened.
# It asserts the async renewal trigger returns an execution id in <1s,
# polls that execution to completion, checks exact deterministic outcomes for BOTH
# payment methods from stored customer data (card tokens, debtor IBANs), and
# cross-checks same-run Prometheus/DB deltas; consumer
# counters are summed across the replica port range.
# It also re-runs the payfold-migrations image as a no-op run-to-completion Job.
# It also requires Prometheus to be scraping both services, Grafana to serve
# the provisioned pipeline dashboard anonymously, and all three
# mock-counterparty instances to report healthy.
# Async settlement is asserted end to end: every payment reaches its
# token/IBAN-predicted terminal state, reconciled row-for-row against the
# settlement inbox; the silent (94) cohorts recover through the sweeper, the
# retriable (95) cohorts re-collect and settle, and the dunning matrix ends in
# exact per-family cancellations with the sweeper provably quiescent.
#
# Usage:
#   scripts/verify.sh [--no-up] [--timeout SECONDS] [--poison|--no-poison]
#
#   --no-up        skip `docker compose up -d --build` (stack already running)
#   --timeout N    max seconds to wait for each long condition (default 900 —
#                  since R26b the terminal waits also cover dunning
#                  re-collection cycles riding behind the drain, and since
#                  R26c the cancellation waits cover exhaustion and expiry)
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

TIMEOUT=900
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
PGPASS="$(env_val POSTGRES_PASSWORD admin)"
PRODUCER_PORT="$(env_val PRODUCER_HTTP_PORT 8080)"
CONSUMER_PORT="$(env_val CONSUMER_HTTP_PORT 8081)"
CONSUMER_PORT_END="$(env_val CONSUMER_HTTP_PORT_END 8083)"
PROM_PORT="$(env_val PROMETHEUS_PORT 9090)"
GRAFANA_PORT="$(env_val GRAFANA_PORT 3000)"
RMQ_USER="$(env_val RABBITMQ_USER guest)"
RMQ_PASS="$(env_val RABBITMQ_PASSWORD guest)"
RMQ_MGMT_PORT="$(env_val RABBITMQ_MGMT_PORT 15672)"
RMQ_QUEUE="$(env_val RABBITMQ_QUEUE billing.renewals.main)"
RMQ_EXCHANGE="$(env_val RABBITMQ_EXCHANGE billing.renewals)"
RMQ_RK="$(env_val RABBITMQ_ROUTINGKEY renewal.requested)"
BANK_PORT="$(env_val BANK_HTTP_PORT 8085)"
BANK_B_PORT="$(env_val BANK_B_HTTP_PORT 8086)"
CARDNET_PORT="$(env_val CARDNET_HTTP_PORT 8087)"
RMQ_DLQ="billing.renewals.dlq"
SETTLEMENT_MAIN="billing.settlements.main"
SETTLEMENT_DLQ="billing.settlements.dlq"

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
# A single replica may bind ANY port in the compose range (assignment within
# "8081-8083:8080" is not deterministic), so health is "some replica is UP".
consumer_up() {
  local port
  for port in $(seq "$CONSUMER_PORT" "$CONSUMER_PORT_END"); do
    if curl -fsS "http://localhost:${port}/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
      return 0
    fi
  done
  return 1
}
consumer_running() { docker compose ps --status running --services 2>/dev/null | grep -qx renewal-consumer; }
bank_up() { curl -fsS "http://localhost:${BANK_PORT}/health" 2>/dev/null | grep -q '"status":"ok"'; }
bank_b_up() { curl -fsS "http://localhost:${BANK_B_PORT}/health" 2>/dev/null | grep -q '"status":"ok"'; }
cardnet_up() { curl -fsS "http://localhost:${CARDNET_PORT}/health" 2>/dev/null | grep -q '"status":"ok"'; }

outbox_drained() { [[ "$(q 'SELECT count(*) FROM renewal_outbox WHERE published_at IS NULL')" == "0" ]]; }

# A today-due card outbox row is correctly billed when the stored card token
# predicts its exact terminal status, reason, channel, and settlement attribution.
# The 95 cohort (R26b) terminates on its attempt-2 re-collection row (|a2 key);
# its attempt-1 failure is asserted separately in the R26b block.
CARD_TERMINAL_MISMATCH_SQL="SELECT count(*) FROM renewal_outbox o
JOIN subscription s ON s.id = o.subscription_id
JOIN customer c ON c.id = s.customer_id
WHERE o.due_date = current_date
  AND c.payment_method = 'card'
  AND NOT EXISTS (
    SELECT 1 FROM payment p
    WHERE p.idempotency_key = 'sub-' || o.subscription_id || '|' || to_char(current_date, 'YYYY-MM-DD')
          || CASE WHEN right(c.card_token, 2) = '95' THEN '|a2' ELSE '' END
      AND p.channel = 'CARD'
      AND p.status = CASE WHEN right(c.card_token, 2) IN ('99','98') THEN 'failed'
                          WHEN right(c.card_token, 2) = '96' THEN 'charged_back'
                          ELSE 'succeeded' END
      AND (p.status = 'succeeded' OR p.failure_reason = CASE right(c.card_token, 2)
                          WHEN '99' THEN 'insufficient_funds' WHEN '98' THEN 'do_not_honor'
                          WHEN '96' THEN 'fraud_dispute' END)
      AND (p.status = 'failed' OR (p.bank_id IS NOT NULL AND p.collection_id IS NOT NULL))
  )"
all_cards_terminal() { [[ "$(q "$CARD_TERMINAL_MISMATCH_SQL")" == "0" ]]; }

# R23d closes the loop: every due SDD renewal must reach the terminal state
# the IBAN rule predicts (docs/architecture.md#mock-bank) after the bank's
# delay + webhook + relay + listener chain, including the 96 chargeback lag.
SDD_TERMINAL_MISMATCH_SQL="SELECT count(*) FROM renewal_outbox o
JOIN subscription s ON s.id = o.subscription_id
JOIN customer c ON c.id = s.customer_id
WHERE o.due_date = current_date
  AND c.payment_method = 'sdd'
  AND NOT EXISTS (
    SELECT 1 FROM payment p
    WHERE p.idempotency_key = 'sub-' || o.subscription_id || '|' || to_char(current_date, 'YYYY-MM-DD')
          || CASE WHEN right(c.debtor_iban, 2) = '95' THEN '|a2' ELSE '' END
      AND p.channel = 'SEPA_DD' AND p.bank_id IS NOT NULL AND p.collection_id IS NOT NULL
      AND p.status = CASE WHEN right(c.debtor_iban, 2) IN ('99','98','97')
                          THEN 'failed' WHEN right(c.debtor_iban, 2) = '96'
                          THEN 'charged_back' ELSE 'succeeded' END
      AND (p.status NOT IN ('failed','charged_back') OR p.failure_reason = CASE right(c.debtor_iban, 2)
                          WHEN '99' THEN 'AM04' WHEN '98' THEN 'AC04' WHEN '97' THEN 'MD01'
                          WHEN '96' THEN 'MD06' END)
  )"
all_sdd_terminal() { [[ "$(q "$SDD_TERMINAL_MISMATCH_SQL")" == "0" ]]; }

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

# Sum an extended-regex metric over every responsive consumer replica port.
# Replicas expose per-process counters; the fleet-wide truth is their sum.
# Echoes the sum, "absent" if no responsive port serves the metric, or
# "unreachable" if no port in the range responds at all.
consumer_prom_sum() {
  local total=0 seen=0 reachable=0 port v
  for port in $(seq "$CONSUMER_PORT" "$CONSUMER_PORT_END"); do
    v="$(prom_val "$port" "$1")"
    [[ "$v" == "unreachable" ]] && continue
    reachable=1
    [[ "$v" == "absent" ]] && continue
    seen=1
    total=$((total + v))
  done
  if (( ! reachable )); then echo unreachable; return; fi
  if (( ! seen )); then echo absent; return; fi
  echo "$total"
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
  processed="$(consumer_prom_sum '^renewals_processed_total\{.*outcome="(succeeded|failed|submitted)"')"
  [[ "$processed" != "absent" && "$processed" != "unreachable" ]]
}
main_queue_empty() { [[ "$(queue_depth "$RMQ_QUEUE")" == "0" ]]; }
settlements_main_empty() { [[ "$(queue_depth "$SETTLEMENT_MAIN")" == "0" ]]; }
settlements_dlq_empty() { [[ "$(queue_depth "$SETTLEMENT_DLQ")" == "0" ]]; }

TRIGGER_CODE=""
TRIGGER_TIME=""
TRIGGER_EXEC_ID=""
trigger_job() { # POST the force trigger; populates TRIGGER_CODE / TRIGGER_TIME / TRIGGER_EXEC_ID
  local body_file meta
  body_file="$(mktemp)"
  meta="$(curl -sS -o "$body_file" -w '%{http_code} %{time_total}' --max-time 30 \
    -X POST "http://localhost:${PRODUCER_PORT}/actuator/renewal-job?force=true" \
    -H 'Content-Type: application/vnd.spring-boot.actuator.v3+json' \
    -H 'Accept: application/json' -d '{}' 2>/dev/null)"
  TRIGGER_CODE="${meta%% *}"
  TRIGGER_TIME="${meta#* }"
  TRIGGER_EXEC_ID="$(grep -o '"executionId":[0-9]*' "$body_file" | head -1 | cut -d: -f2)"
  rm -f "$body_file"
}

# assert_trigger <label> — three checks: HTTP 200, <1s wall time (the R10
# ratchet: the POST must return immediately regardless of scale), executionId
# present for the status poll.
assert_trigger() {
  local label="$1"
  if [[ "$TRIGGER_CODE" == "200" ]]; then
    pass "${label} returned 200"
  else
    fail "${label} returned 200" "HTTP ${TRIGGER_CODE:-none}"
  fi
  if awk -v t="${TRIGGER_TIME:-999}" 'BEGIN { exit !(t < 1.0) }'; then
    pass "${label} returned in <1s (${TRIGGER_TIME}s)"
  else
    fail "${label} returned in <1s" "time_total=${TRIGGER_TIME:-unknown}s"
  fi
  if [[ "$TRIGGER_EXEC_ID" =~ ^[0-9]+$ ]]; then
    pass "${label} response contains executionId (${TRIGGER_EXEC_ID})"
  else
    fail "${label} response contains executionId" "no executionId in response body"
  fi
}

job_status() { # execution id -> status string, or "unreachable"
  local body
  body="$(curl -fsS "http://localhost:${PRODUCER_PORT}/actuator/renewal-job/$1" 2>/dev/null)" \
    || { echo unreachable; return; }
  echo "$body" | grep -o '"status":"[A-Z]*"' | head -1 | cut -d'"' -f4
}
job_completed() { [[ "$(job_status "$TRIGGER_EXEC_ID")" == "COMPLETED" ]]; }

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
# R18: the migrations image must work as a run-to-completion Job (the Kubernetes
# pattern): configured only by FLYWAY_* env vars, exit 0 on success, and a re-run
# against the already-migrated stack database is a no-op. The image was just
# built by compose (flyway service); "postgres" resolves on the stack network.
MIG_JOB_DETAIL=""
migrations_job_rerun() {
  local net out
  net="$(docker inspect pg_payfold --format '{{range $k, $v := .NetworkSettings.Networks}}{{println $k}}{{end}}' 2>/dev/null | head -1)"
  if [[ -z "$net" ]]; then
    MIG_JOB_DETAIL="cannot determine the pg_payfold container network"
    return 1
  fi
  if ! out="$(docker run --rm --network "$net" \
      -e FLYWAY_URL="jdbc:postgresql://postgres:5432/${PGDB}" \
      -e FLYWAY_USER="$PGUSER" \
      -e FLYWAY_PASSWORD="$PGPASS" \
      payfold-migrations:latest 2>&1)"; then
    MIG_JOB_DETAIL="nonzero exit: $(echo "$out" | tail -2 | tr '\n' ' ')"
    return 1
  fi
  if ! echo "$out" | grep -q "No migration necessary"; then
    MIG_JOB_DETAIL="exit 0 but not a no-op re-run"
    return 1
  fi
  return 0
}
if migrations_job_rerun; then
  pass "migrations image re-runs as a no-op Job (exit 0)"
else
  fail "migrations image re-runs as a no-op Job (exit 0)" "$MIG_JOB_DETAIL"
fi
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

# R23a: the mock bank is part of the stack's definition of working.
wait_for "mock-bank /health ok" bank_up
wait_for "mock-bank-b /health ok" bank_b_up
wait_for "mock-card /health ok" cardnet_up

wait_for "producer /actuator/prometheus serves outbox counters" producer_prometheus_ready || summary
wait_for "consumer /actuator/prometheus serves renewals counter" consumer_prometheus_ready || summary

# R21: the observability layer is part of the stack's definition of working —
# Prometheus must be up and actually scraping both services, and Grafana must
# have provisioned the pipeline dashboard for anonymous viewing.
prometheus_healthy() { curl -fsS "http://localhost:${PROM_PORT}/-/healthy" >/dev/null 2>&1; }
prometheus_scraping() {
  local body
  body="$(curl -fsS "http://localhost:${PROM_PORT}/api/v1/query?query=up" 2>/dev/null)" || return 1
  echo "$body" | grep -q '"job":"payfold-producer"' \
    && echo "$body" | grep -q '"job":"payfold-consumer"' \
    && ! echo "$body" | grep -q '"value":\[[0-9.]*,"0"\]'
}
grafana_dashboard_provisioned() {
  curl -fsS "http://localhost:${GRAFANA_PORT}/api/search?query=Payfold" 2>/dev/null \
    | grep -q '"uid":"payfold-pipeline"'
}
wait_for "prometheus healthy"                              prometheus_healthy
wait_for "prometheus scraping producer and consumer"       prometheus_scraping
wait_for "grafana serves the provisioned pipeline dashboard" grafana_dashboard_provisioned

M_INS_BEFORE="$(prom_val "$PRODUCER_PORT" '^outbox_inserted_total ')"
M_PUB_BEFORE="$(prom_val "$PRODUCER_PORT" '^outbox_published_total ')"
M_PROC_BEFORE="$(consumer_prom_sum '^renewals_processed_total\{.*outcome="(succeeded|failed|submitted)"')"
M_RECOVERED_BEFORE="$(consumer_prom_sum '^settlements_recovered_total')"
[[ "$M_RECOVERED_BEFORE" == "absent" ]] && M_RECOVERED_BEFORE=0
M_DUNNING_RETRIABLE_BEFORE="$(consumer_prom_sum '^dunning_transitions_total\{class="retriable"')"
M_DUNNING_HARD_BEFORE="$(consumer_prom_sum '^dunning_transitions_total\{class="hard_fail"')"
M_DUNNING_DISPUTE_BEFORE="$(consumer_prom_sum '^dunning_transitions_total\{class="dispute"')"
[[ "$M_DUNNING_RETRIABLE_BEFORE" == "absent" ]] && M_DUNNING_RETRIABLE_BEFORE=0
[[ "$M_DUNNING_HARD_BEFORE" == "absent" ]] && M_DUNNING_HARD_BEFORE=0
[[ "$M_DUNNING_DISPUTE_BEFORE" == "absent" ]] && M_DUNNING_DISPUTE_BEFORE=0
M_CANCEL_EXHAUSTED_BEFORE="$(consumer_prom_sum '^dunning_cancellations_total\{cause="exhausted"')"
M_CANCEL_EXPIRED_BEFORE="$(consumer_prom_sum '^dunning_cancellations_total\{cause="grace_expired"')"
[[ "$M_CANCEL_EXHAUSTED_BEFORE" == "absent" ]] && M_CANCEL_EXHAUSTED_BEFORE=0
[[ "$M_CANCEL_EXPIRED_BEFORE" == "absent" ]] && M_CANCEL_EXPIRED_BEFORE=0
DB_OUTBOX_BEFORE="$(q 'SELECT count(*) FROM renewal_outbox')"
DB_PUB_BEFORE="$(q 'SELECT count(*) FROM renewal_outbox WHERE published_at IS NOT NULL')"

note "triggering renewal job (async endpoint — POST returns the execution id)…"
trigger_job
assert_trigger "renewal job trigger"
if [[ "$TRIGGER_EXEC_ID" =~ ^[0-9]+$ ]]; then
  wait_for "job execution ${TRIGGER_EXEC_ID} reached COMPLETED" job_completed
else
  fail "job execution reached COMPLETED" "no executionId to poll"
fi

N_DUE="$(q 'SELECT count(*) FROM renewal_outbox WHERE due_date = current_date')"
if [[ -n "$N_DUE" && "$N_DUE" -gt 0 ]]; then
  pass "outbox contains renewals due today (${N_DUE})"
else
  fail "outbox contains renewals due today" "count=${N_DUE:-error}"
fi
N_CARD_DUE="$(q "SELECT count(*) FROM renewal_outbox o JOIN subscription s ON s.id = o.subscription_id JOIN customer c ON c.id = s.customer_id WHERE o.due_date = current_date AND c.payment_method = 'card'")"
N_SDD_DUE="$(q "SELECT count(*) FROM renewal_outbox o JOIN subscription s ON s.id = o.subscription_id JOIN customer c ON c.id = s.customer_id WHERE o.due_date = current_date AND c.payment_method = 'sdd'")"
if [[ "$N_CARD_DUE" =~ ^[0-9]+$ && "$N_SDD_DUE" =~ ^[0-9]+$ && $((N_CARD_DUE + N_SDD_DUE)) == "$N_DUE" ]]; then
  pass "due renewals partition into card + sdd cohorts (${N_CARD_DUE} card / ${N_SDD_DUE} sdd)"
else
  fail "due renewals partition into card + sdd cohorts" "card=${N_CARD_DUE:-error} sdd=${N_SDD_DUE:-error} due=${N_DUE:-error}"
fi
SEED_SDD_PERCENT_VAL="$(env_val SEED_SDD_PERCENT 20)"
if [[ "$SEED_SDD_PERCENT_VAL" != "0" && "$N_SDD_DUE" == "0" ]]; then
  fail "SDD cohort present when SEED_SDD_PERCENT > 0" "N_SDD_DUE=0 with SEED_SDD_PERCENT=${SEED_SDD_PERCENT_VAL}"
else
  pass "SDD cohort present when SEED_SDD_PERCENT > 0 (${N_SDD_DUE})"
fi

wait_for "outbox fully published"                outbox_drained
wait_for "every due card renewal reached its token-predicted terminal payment" all_cards_terminal
wait_for "every due SDD renewal reached its bank-predicted terminal payment" all_sdd_terminal

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
  current="$(consumer_prom_sum '^renewals_processed_total\{.*outcome="(succeeded|failed|submitted)"')"
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
  wait_for "renewals_processed_total{succeeded,failed,submitted} delta matches rows published this run (${DB_PUB_DELTA})" processed_metric_delta_matches
else
  fail "renewals_processed_total{succeeded,failed,submitted} delta matches rows published this run (${DB_PUB_DELTA})" \
    "invalid baseline=${M_PROC_BEFORE} or DB delta=${DB_PUB_DELTA}"
fi
wait_for "spring_batch_job_seconds recorded on producer" batch_job_timer_recorded

# The 95 cohort (R26b) fails its first attempt by rule, so base-key failed
# counts and reason maps include it; its recovery is asserted separately.
N_RETRY_SDD="$(q "SELECT count(*) FROM renewal_outbox o JOIN subscription s ON s.id = o.subscription_id JOIN customer c ON c.id = s.customer_id WHERE o.due_date = current_date AND c.payment_method = 'sdd' AND right(c.debtor_iban, 2) = '95'")"
N_RETRY_CARD="$(q "SELECT count(*) FROM renewal_outbox o JOIN subscription s ON s.id = o.subscription_id JOIN customer c ON c.id = s.customer_id WHERE o.due_date = current_date AND c.payment_method = 'card' AND right(c.card_token, 2) = '95'")"
N_RETRY=$((N_RETRY_SDD + N_RETRY_CARD))

EXPECTED_CARD_FAILED="$(q "SELECT count(*) FROM renewal_outbox o JOIN subscription s ON s.id = o.subscription_id JOIN customer c ON c.id = s.customer_id WHERE o.due_date = current_date AND c.payment_method = 'card' AND right(c.card_token, 2) IN ('99','98','95')")"
ACTUAL_CARD_FAILED="$(q "SELECT count(*) FROM payment WHERE status = 'failed' AND channel = 'CARD' AND idempotency_key LIKE 'sub-%|' || to_char(current_date, 'YYYY-MM-DD')")"
if [[ -n "$EXPECTED_CARD_FAILED" && "$ACTUAL_CARD_FAILED" == "$EXPECTED_CARD_FAILED" ]]; then
  pass "card failed count matches the token rule exactly (${ACTUAL_CARD_FAILED}/${N_CARD_DUE})"
else
  fail "card failed count matches the token rule exactly" "expected=${EXPECTED_CARD_FAILED:-error} actual=${ACTUAL_CARD_FAILED:-error}"
fi
CARD_REASON_MISMATCH="$(q "SELECT count(*) FROM payment p JOIN charge ch ON ch.id = p.charge_id JOIN subscription s ON s.id = ch.subscription_id JOIN customer c ON c.id = s.customer_id
WHERE p.status = 'failed' AND p.channel = 'CARD'
  AND p.idempotency_key LIKE 'sub-%|' || to_char(current_date, 'YYYY-MM-DD')
  AND p.failure_reason IS DISTINCT FROM CASE right(c.card_token, 2) WHEN '99' THEN 'insufficient_funds' WHEN '98' THEN 'do_not_honor' WHEN '95' THEN 'insufficient_funds' END")"
if [[ "$CARD_REASON_MISMATCH" == "0" ]]; then
  pass "every failed card payment carries its token-predicted reason"
else
  fail "every failed card payment carries its token-predicted reason" "count=${CARD_REASON_MISMATCH:-error}"
fi
EXPECTED_CARD_CHARGED_BACK="$(q "SELECT count(*) FROM renewal_outbox o JOIN subscription s ON s.id = o.subscription_id JOIN customer c ON c.id = s.customer_id WHERE o.due_date = current_date AND c.payment_method = 'card' AND right(c.card_token, 2) = '96'")"
ACTUAL_CARD_CHARGED_BACK="$(q "SELECT count(*) FROM payment WHERE status = 'charged_back' AND channel = 'CARD' AND idempotency_key LIKE 'sub-%|' || to_char(current_date, 'YYYY-MM-DD')")"
if [[ -n "$EXPECTED_CARD_CHARGED_BACK" && "$ACTUAL_CARD_CHARGED_BACK" == "$EXPECTED_CARD_CHARGED_BACK" ]]; then
  pass "card charged-back count matches the token rule exactly (${ACTUAL_CARD_CHARGED_BACK}/${EXPECTED_CARD_CHARGED_BACK})"
else
  fail "card charged-back count matches the token rule exactly" "expected=${EXPECTED_CARD_CHARGED_BACK:-error} actual=${ACTUAL_CARD_CHARGED_BACK:-error}"
fi

STUCK_SUBMITTED="$(q "SELECT count(*) FROM payment WHERE status = 'submitted' AND idempotency_key LIKE 'sub-%|' || to_char(current_date, 'YYYY-MM-DD')")"
if [[ "$STUCK_SUBMITTED" == "0" ]]; then
  pass "zero payments stuck submitted after settlement"
else
  fail "zero payments stuck submitted after settlement" "count=${STUCK_SUBMITTED:-error}"
fi
EXPECTED_SDD_FAILED="$(q "SELECT count(*) FROM renewal_outbox o JOIN subscription s ON s.id = o.subscription_id JOIN customer c ON c.id = s.customer_id WHERE o.due_date = current_date AND c.payment_method = 'sdd' AND right(c.debtor_iban, 2) IN ('99','98','97','95')")"
ACTUAL_SDD_FAILED="$(q "SELECT count(*) FROM payment WHERE status = 'failed' AND channel = 'SEPA_DD' AND idempotency_key LIKE 'sub-%|' || to_char(current_date, 'YYYY-MM-DD')")"
if [[ -n "$EXPECTED_SDD_FAILED" && "$ACTUAL_SDD_FAILED" == "$EXPECTED_SDD_FAILED" ]]; then
  pass "SDD failed count matches the IBAN rule exactly (${ACTUAL_SDD_FAILED}/${N_SDD_DUE})"
else
  fail "SDD failed count matches the IBAN rule exactly" "expected=${EXPECTED_SDD_FAILED:-error} actual=${ACTUAL_SDD_FAILED:-error}"
fi
REASON_MISMATCH="$(q "SELECT count(*) FROM payment p JOIN charge ch ON ch.id = p.charge_id JOIN subscription s ON s.id = ch.subscription_id JOIN customer c ON c.id = s.customer_id
WHERE p.status = 'failed' AND p.channel = 'SEPA_DD'
  AND p.idempotency_key LIKE 'sub-%|' || to_char(current_date, 'YYYY-MM-DD')
  AND p.failure_reason IS DISTINCT FROM CASE right(c.debtor_iban, 2) WHEN '99' THEN 'AM04' WHEN '98' THEN 'AC04' WHEN '97' THEN 'MD01' WHEN '95' THEN 'AM04' END")"
if [[ "$REASON_MISMATCH" == "0" ]]; then
  pass "every failed SDD payment carries its predicted ISO reason"
else
  fail "every failed SDD payment carries its predicted ISO reason" "count=${REASON_MISMATCH:-error}"
fi

# Reconciliation: the 96 cohort produces TWO notifications (settled + the
# applied chargeback), everything else one. Chargebacks lag by
# BANK_CHARGEBACK_LAG_SECONDS, so this is a bounded wait, not a single read.
# Since R26b the exactness is per collection family: base collections stay
# exactly predictable, the 95 cohort adds exactly one attempt-2 row each, and
# the 99 cohort's re-collections are bounded by DUNNING_MAX_ATTEMPTS since
# R26c and asserted exactly in the cancellation block below.
N_96_DUE="$(q "SELECT count(*) FROM renewal_outbox o JOIN subscription s ON s.id = o.subscription_id JOIN customer c ON c.id = s.customer_id WHERE o.due_date = current_date AND c.payment_method = 'sdd' AND right(c.debtor_iban, 2) = '96'")"
N_CARD_AUTH="$(q "SELECT count(*) FROM renewal_outbox o JOIN subscription s ON s.id = o.subscription_id JOIN customer c ON c.id = s.customer_id WHERE o.due_date = current_date AND c.payment_method = 'card' AND right(c.card_token, 2) NOT IN ('99','98')")"
N_CARD_96="$(q "SELECT count(*) FROM renewal_outbox o JOIN subscription s ON s.id = o.subscription_id JOIN customer c ON c.id = s.customer_id WHERE o.due_date = current_date AND c.payment_method = 'card' AND right(c.card_token, 2) = '96'")"
# Card-95 attempt 1 declines synchronously (no notification); its settled row
# arrives under the |a2 id and is counted by the retry-family check below.
EXPECTED_INBOX=$((N_SDD_DUE + N_96_DUE + N_CARD_AUTH - N_RETRY_CARD + N_CARD_96))
BASE_INBOX_SQL="SELECT count(*) FROM settlement_inbox WHERE notification_id NOT LIKE '%|a%'"
inbox_complete() { [[ "$(q "$BASE_INBOX_SQL")" -ge "$EXPECTED_INBOX" ]] 2>/dev/null; }
wait_for "settlement inbox received every predicted base notification (${EXPECTED_INBOX})" inbox_complete
INBOX_TOTAL="$(q "$BASE_INBOX_SQL")"
if [[ "$INBOX_TOTAL" == "$EXPECTED_INBOX" ]]; then
  pass "base-collection inbox row count matches the prediction exactly (${INBOX_TOTAL})"
else
  fail "base-collection inbox row count matches the prediction exactly" "expected=${EXPECTED_INBOX} actual=${INBOX_TOTAL:-error}"
fi
RETRY_INBOX="$(q "SELECT count(*) FROM settlement_inbox i JOIN payment p ON i.bank_id = p.bank_id AND i.notification_id = p.collection_id || ':1' JOIN charge ch ON ch.id = p.charge_id JOIN subscription s ON s.id = ch.subscription_id JOIN customer c ON c.id = s.customer_id
WHERE p.attempt = 2 AND ((c.payment_method = 'sdd' AND right(c.debtor_iban, 2) = '95') OR (c.payment_method = 'card' AND right(c.card_token, 2) = '95'))")"
if [[ "$RETRY_INBOX" == "$N_RETRY" ]]; then
  pass "95-cohort attempt-2 settlements each landed one inbox row (${RETRY_INBOX})"
else
  fail "95-cohort attempt-2 settlements each landed one inbox row" "expected=${N_RETRY} actual=${RETRY_INBOX:-error}"
fi
inbox_relayed() { [[ "$(q 'SELECT count(*) FROM settlement_inbox WHERE published_at IS NULL')" == "0" ]]; }
wait_for "every inbox row relayed to the settlements queue" inbox_relayed
ACTUAL_CHARGED_BACK="$(q "SELECT count(*) FROM payment WHERE status = 'charged_back' AND channel = 'SEPA_DD' AND idempotency_key LIKE 'sub-%|' || to_char(current_date, 'YYYY-MM-DD')")"
if [[ -n "$N_96_DUE" && "$ACTUAL_CHARGED_BACK" == "$N_96_DUE" ]]; then
  pass "charged-back count matches the IBAN rule exactly (${ACTUAL_CHARGED_BACK}/${N_96_DUE})"
else
  fail "charged-back count matches the IBAN rule exactly" "expected=${N_96_DUE:-error} actual=${ACTUAL_CHARGED_BACK:-error}"
fi
DISPUTED_MISMATCH="$(q "SELECT count(*) FROM payment p JOIN charge ch ON ch.id = p.charge_id JOIN invoice i ON i.id = ch.invoice_id
WHERE p.status = 'charged_back' AND p.idempotency_key LIKE 'sub-%|' || to_char(current_date, 'YYYY-MM-DD') AND i.status <> 'disputed'")"
if [[ "$DISPUTED_MISMATCH" == "0" ]]; then
  pass "every charged-back payment's invoice is marked disputed"
else
  fail "every charged-back payment's invoice is marked disputed" "count=${DISPUTED_MISMATCH:-error}"
fi
CHARGEBACK_ROLLBACKS="$(q "SELECT count(*) FROM renewal_outbox o JOIN subscription s ON s.id = o.subscription_id JOIN customer c ON c.id = s.customer_id
WHERE o.due_date = current_date AND c.payment_method = 'sdd' AND right(c.debtor_iban, 2) = '96' AND s.renewed_at < current_date")"
if [[ "$CHARGEBACK_ROLLBACKS" == "0" ]]; then
  pass "no chargeback rolled a subscription back (recorded fact, D16)"
else
  fail "no chargeback rolled a subscription back (recorded fact, D16)" "count=${CHARGEBACK_ROLLBACKS:-error}"
fi
UNTRACED="$(q "SELECT count(*) FROM payment p WHERE (
    (p.channel = 'SEPA_DD' AND p.status IN ('succeeded','failed','charged_back'))
    OR (p.channel = 'CARD' AND p.status IN ('succeeded','charged_back'))
  )
  AND p.idempotency_key LIKE 'sub-%|' || to_char(current_date, 'YYYY-MM-DD')
  AND NOT EXISTS (SELECT 1 FROM settlement_inbox i WHERE i.bank_id = p.bank_id AND i.notification_id = p.collection_id || ':1')")"
if [[ "$UNTRACED" == "0" ]]; then
  pass "every asynchronously terminal payment traces to an inbox notification"
else
  fail "every asynchronously terminal payment traces to an inbox notification" "count=${UNTRACED:-error}"
fi

# R23e: per-bank attribution. The compose default routing is bank-a: BE,FR;
# bank-b: NL,IE — assert the stored attribution matches it row-for-row, and
# each bank's base-collection inbox share matches its routed cohort exactly.
ROUTING_MISMATCH="$(q "SELECT count(*) FROM payment p JOIN charge ch ON ch.id = p.charge_id JOIN subscription s ON s.id = ch.subscription_id JOIN customer c ON c.id = s.customer_id
WHERE p.channel = 'SEPA_DD' AND p.idempotency_key LIKE 'sub-%|' || to_char(current_date, 'YYYY-MM-DD')
  AND p.bank_id IS DISTINCT FROM CASE WHEN c.country IN ('BE','FR') THEN 'bank-a' ELSE 'bank-b' END")"
if [[ "$ROUTING_MISMATCH" == "0" ]]; then
  pass "every SDD payment routed to its country's bank (BE,FR→bank-a; NL,IE→bank-b)"
else
  fail "every SDD payment routed to its country's bank" "count=${ROUTING_MISMATCH:-error}"
fi
for BANK in bank-a bank-b cardnet; do
  if [[ "$BANK" == "cardnet" ]]; then
    EXPECTED_BANK_INBOX=$((N_CARD_AUTH - N_RETRY_CARD + N_CARD_96))
  else
    if [[ "$BANK" == "bank-a" ]]; then COUNTRIES="('BE','FR')"; else COUNTRIES="('NL','IE')"; fi
    EXPECTED_BANK_INBOX="$(q "SELECT count(*) + count(*) FILTER (WHERE right(c.debtor_iban, 2) = '96') FROM renewal_outbox o JOIN subscription s ON s.id = o.subscription_id JOIN customer c ON c.id = s.customer_id
WHERE o.due_date = current_date AND c.payment_method = 'sdd' AND c.country IN ${COUNTRIES}")"
  fi
  ACTUAL_BANK_INBOX="$(q "SELECT count(*) FROM settlement_inbox WHERE bank_id = '${BANK}' AND notification_id NOT LIKE '%|a%'")"
  if [[ -n "$EXPECTED_BANK_INBOX" && "$ACTUAL_BANK_INBOX" == "$EXPECTED_BANK_INBOX" ]]; then
    pass "${BANK} base inbox rows match its routed cohort exactly (${ACTUAL_BANK_INBOX})"
  else
    fail "${BANK} base inbox rows match its routed cohort exactly" "expected=${EXPECTED_BANK_INBOX:-error} actual=${ACTUAL_BANK_INBOX:-error}"
  fi
done

# --- R28: deterministic recovery of the silent (suffix-94) cohorts (D18) ---
N_SDD_SILENT="$(q "SELECT count(*) FROM renewal_outbox o JOIN subscription s ON s.id = o.subscription_id JOIN customer c ON c.id = s.customer_id WHERE o.due_date = current_date AND c.payment_method = 'sdd' AND right(c.debtor_iban, 2) = '94'")"
N_CARD_SILENT="$(q "SELECT count(*) FROM renewal_outbox o JOIN subscription s ON s.id = o.subscription_id JOIN customer c ON c.id = s.customer_id WHERE o.due_date = current_date AND c.payment_method = 'card' AND right(c.card_token, 2) = '94'")"
SEED_SDD_SILENT_PERCENT_VAL="$(env_val SEED_SDD_SILENT_PERCENT 2)"
if [[ "$SEED_SDD_SILENT_PERCENT_VAL" != "0" && "$N_SDD_SILENT" == "0" ]]; then
  fail "silent SDD cohort present when SEED_SDD_SILENT_PERCENT > 0" "N_SDD_SILENT=0 with SEED_SDD_SILENT_PERCENT=${SEED_SDD_SILENT_PERCENT_VAL}"
else
  pass "silent SDD cohort present when SEED_SDD_SILENT_PERCENT > 0 (${N_SDD_SILENT})"
fi
SEED_CARD_SILENT_PERCENT_VAL="$(env_val SEED_CARD_SILENT_PERCENT 2)"
if [[ "$SEED_CARD_SILENT_PERCENT_VAL" != "0" && "$N_CARD_SILENT" == "0" ]]; then
  fail "silent card cohort present when SEED_CARD_SILENT_PERCENT > 0" "N_CARD_SILENT=0 with SEED_CARD_SILENT_PERCENT=${SEED_CARD_SILENT_PERCENT_VAL}"
else
  pass "silent card cohort present when SEED_CARD_SILENT_PERCENT > 0 (${N_CARD_SILENT})"
fi

EXPECTED_SILENT=$((N_SDD_SILENT + N_CARD_SILENT))
ACTUAL_SILENT_SUCCEEDED="$(q "SELECT count(*) FROM renewal_outbox o JOIN subscription s ON s.id = o.subscription_id JOIN customer c ON c.id = s.customer_id JOIN payment p ON p.idempotency_key = 'sub-' || o.subscription_id || '|' || to_char(o.due_date, 'YYYY-MM-DD')
WHERE o.due_date = current_date AND ((c.payment_method = 'sdd' AND right(c.debtor_iban, 2) = '94') OR (c.payment_method = 'card' AND right(c.card_token, 2) = '94')) AND p.status = 'succeeded'")"
if [[ "$ACTUAL_SILENT_SUCCEEDED" == "$EXPECTED_SILENT" ]]; then
  pass "every silent payment succeeded through recovery (${ACTUAL_SILENT_SUCCEEDED})"
else
  fail "every silent payment succeeded through recovery" "expected=${EXPECTED_SILENT} actual=${ACTUAL_SILENT_SUCCEEDED:-error}"
fi

ACTUAL_SILENT_INBOX="$(q "SELECT count(*) FROM renewal_outbox o JOIN subscription s ON s.id = o.subscription_id JOIN customer c ON c.id = s.customer_id JOIN payment p ON p.idempotency_key = 'sub-' || o.subscription_id || '|' || to_char(o.due_date, 'YYYY-MM-DD') JOIN settlement_inbox i ON i.bank_id = p.bank_id AND i.notification_id = p.collection_id || ':1'
WHERE o.due_date = current_date AND ((c.payment_method = 'sdd' AND right(c.debtor_iban, 2) = '94') OR (c.payment_method = 'card' AND right(c.card_token, 2) = '94'))")"
if [[ "$ACTUAL_SILENT_INBOX" == "$EXPECTED_SILENT" ]]; then
  pass "every silent payment has a synthesized inbox row (${ACTUAL_SILENT_INBOX})"
else
  fail "every silent payment has a synthesized inbox row" "expected=${EXPECTED_SILENT} actual=${ACTUAL_SILENT_INBOX:-error}"
fi

recovered_metric_delta_matches() {
  local current
  current="$(consumer_prom_sum '^settlements_recovered_total')"
  [[ "$current" =~ ^[0-9]+$ ]] \
    && (( current - M_RECOVERED_BEFORE == EXPECTED_SILENT ))
}
if [[ "$M_RECOVERED_BEFORE" =~ ^[0-9]+$ ]]; then
  wait_for "settlements_recovered_total delta matches the silent cohorts exactly (${EXPECTED_SILENT})" recovered_metric_delta_matches
else
  fail "settlements_recovered_total delta matches the silent cohorts exactly (${EXPECTED_SILENT})" \
    "invalid baseline=${M_RECOVERED_BEFORE}"
fi

for BANK_AND_PORT in "bank-a:${BANK_PORT}" "bank-b:${BANK_B_PORT}" "cardnet:${CARDNET_PORT}"; do
  BANK="${BANK_AND_PORT%%:*}"
  PORT="${BANK_AND_PORT#*:}"
  BANK_GIVEUPS="$(curl -fsS "http://localhost:${PORT}/metrics" 2>/dev/null | grep '^bank_webhook_giveups_total ' | awk '{print $2}')"
  if [[ "$BANK_GIVEUPS" == "0.0" ]]; then
    pass "${BANK} webhook give-ups are zero"
  else
    fail "${BANK} webhook give-ups are zero" "value=${BANK_GIVEUPS:-unreadable}"
  fi
done

# --- R26b: retriable failures re-collect as constraint-keyed attempts ---
retry_cohort_recovered() {
  local recovered
  recovered="$(q "SELECT count(*) FROM renewal_outbox o JOIN subscription s ON s.id = o.subscription_id JOIN customer c ON c.id = s.customer_id WHERE o.due_date = current_date AND ((c.payment_method = 'sdd' AND right(c.debtor_iban, 2) = '95') OR (c.payment_method = 'card' AND right(c.card_token, 2) = '95')) AND s.status = 'active' AND s.grace_until IS NULL")"
  [[ "$recovered" == "$N_RETRY" ]]
}
wait_for "95-cohort recovered to active with grace cleared (${N_RETRY})" retry_cohort_recovered

RETRY_ATTEMPT_ONE_FAILED="$(q "SELECT count(*) FROM renewal_outbox o JOIN subscription s ON s.id = o.subscription_id JOIN customer c ON c.id = s.customer_id JOIN payment p ON p.idempotency_key = 'sub-' || o.subscription_id || '|' || to_char(o.due_date, 'YYYY-MM-DD') WHERE o.due_date = current_date AND p.attempt = 1 AND p.status = 'failed' AND ((c.payment_method = 'sdd' AND right(c.debtor_iban, 2) = '95' AND p.failure_reason = 'AM04') OR (c.payment_method = 'card' AND right(c.card_token, 2) = '95' AND p.failure_reason = 'insufficient_funds'))")"
if [[ "$RETRY_ATTEMPT_ONE_FAILED" == "$N_RETRY" ]]; then
  pass "every 95-cohort renewal failed attempt 1 with its retriable reason (${RETRY_ATTEMPT_ONE_FAILED})"
else
  fail "every 95-cohort renewal failed attempt 1 with its retriable reason" "expected=${N_RETRY} actual=${RETRY_ATTEMPT_ONE_FAILED:-error}"
fi

RETRY_ATTEMPT_TWO_SETTLED="$(q "SELECT count(*) FROM renewal_outbox o JOIN subscription s ON s.id = o.subscription_id JOIN customer c ON c.id = s.customer_id JOIN payment p ON p.idempotency_key = 'sub-' || o.subscription_id || '|' || to_char(o.due_date, 'YYYY-MM-DD') || '|a2' WHERE o.due_date = current_date AND ((c.payment_method = 'sdd' AND right(c.debtor_iban, 2) = '95') OR (c.payment_method = 'card' AND right(c.card_token, 2) = '95')) AND p.status = 'succeeded' AND p.attempt = 2")"
if [[ "$RETRY_ATTEMPT_TWO_SETTLED" == "$N_RETRY" ]]; then
  pass "every 95-cohort renewal settled on attempt 2 (${RETRY_ATTEMPT_TWO_SETTLED})"
else
  fail "every 95-cohort renewal settled on attempt 2" "expected=${N_RETRY} actual=${RETRY_ATTEMPT_TWO_SETTLED:-error}"
fi

RETRIABLE_99_SUCCEEDED="$(q "SELECT count(*) FROM renewal_outbox o JOIN subscription s ON s.id = o.subscription_id JOIN customer c ON c.id = s.customer_id JOIN payment p ON (p.idempotency_key = 'sub-' || o.subscription_id || '|' || to_char(o.due_date, 'YYYY-MM-DD') OR p.idempotency_key LIKE 'sub-' || o.subscription_id || '|' || to_char(o.due_date, 'YYYY-MM-DD') || '|a%') WHERE o.due_date = current_date AND ((c.payment_method = 'sdd' AND right(c.debtor_iban, 2) = '99') OR (c.payment_method = 'card' AND right(c.card_token, 2) = '99')) AND p.status = 'succeeded'")"
if [[ "$RETRIABLE_99_SUCCEEDED" == "0" ]]; then
  pass "no 99-cohort payment ever succeeded"
else
  fail "no 99-cohort payment ever succeeded" "count=${RETRIABLE_99_SUCCEEDED:-error}"
fi

HARD_OR_DISPUTE_RETRIES="$(q "SELECT count(*) FROM renewal_outbox o JOIN subscription s ON s.id = o.subscription_id JOIN customer c ON c.id = s.customer_id JOIN payment p ON (p.idempotency_key = 'sub-' || o.subscription_id || '|' || to_char(o.due_date, 'YYYY-MM-DD') OR p.idempotency_key LIKE 'sub-' || o.subscription_id || '|' || to_char(o.due_date, 'YYYY-MM-DD') || '|a%') WHERE o.due_date = current_date AND p.attempt > 1 AND ((c.payment_method = 'sdd' AND right(c.debtor_iban, 2) IN ('98','97','96')) OR (c.payment_method = 'card' AND right(c.card_token, 2) IN ('98','96')))")"
if [[ "$HARD_OR_DISPUTE_RETRIES" == "0" ]]; then
  pass "hard-fail and dispute cohorts are never retried"
else
  fail "hard-fail and dispute cohorts are never retried" "count=${HARD_OR_DISPUTE_RETRIES:-error}"
fi

# --- R26a/R26c: terminal outcomes transit past_due grace and end canceled ---
# Entries into grace are asserted by the cumulative transition deltas below;
# the end state is the R26c cancellation matrix, reached on the sweeper's
# clock (bounded waits, not reads). The past_due peak between the two is
# timing-shaped and deliberately unasserted.
N_PD_RETRIABLE="$(q "SELECT count(*) FROM renewal_outbox o JOIN subscription s ON s.id = o.subscription_id JOIN customer c ON c.id = s.customer_id WHERE o.due_date = current_date AND ((c.payment_method = 'sdd' AND right(c.debtor_iban, 2) = '99') OR (c.payment_method = 'card' AND right(c.card_token, 2) = '99'))")"
N_PD_HARD="$(q "SELECT count(*) FROM renewal_outbox o JOIN subscription s ON s.id = o.subscription_id JOIN customer c ON c.id = s.customer_id WHERE o.due_date = current_date AND ((c.payment_method = 'sdd' AND right(c.debtor_iban, 2) IN ('98','97')) OR (c.payment_method = 'card' AND right(c.card_token, 2) = '98'))")"
N_PD_DISPUTE="$(q "SELECT count(*) FROM renewal_outbox o JOIN subscription s ON s.id = o.subscription_id JOIN customer c ON c.id = s.customer_id WHERE o.due_date = current_date AND ((c.payment_method = 'sdd' AND right(c.debtor_iban, 2) = '96') OR (c.payment_method = 'card' AND right(c.card_token, 2) = '96'))")"
N_SDD_99="$(q "SELECT count(*) FROM renewal_outbox o JOIN subscription s ON s.id = o.subscription_id JOIN customer c ON c.id = s.customer_id WHERE o.due_date = current_date AND c.payment_method = 'sdd' AND right(c.debtor_iban, 2) = '99'")"
EXPECTED_CANCELED=$((N_PD_RETRIABLE + N_PD_HARD + N_PD_DISPUTE))

due_cohort_canceled() {
  local canceled
  canceled="$(q "SELECT count(*) FROM renewal_outbox o JOIN subscription s ON s.id = o.subscription_id WHERE o.due_date = current_date AND s.status = 'canceled'")"
  [[ "$canceled" == "$EXPECTED_CANCELED" ]]
}
wait_for "due-cohort cancellations match the suffix matrix exactly (${EXPECTED_CANCELED})" due_cohort_canceled

CANCELED_RETRIABLE="$(q "SELECT count(*) FROM renewal_outbox o JOIN subscription s ON s.id = o.subscription_id JOIN customer c ON c.id = s.customer_id WHERE o.due_date = current_date AND s.status = 'canceled' AND ((c.payment_method = 'sdd' AND right(c.debtor_iban, 2) = '99') OR (c.payment_method = 'card' AND right(c.card_token, 2) = '99'))")"
if [[ "$CANCELED_RETRIABLE" == "$N_PD_RETRIABLE" ]]; then
  pass "99 family ended exhausted-canceled exactly (${CANCELED_RETRIABLE})"
else
  fail "99 family ended exhausted-canceled exactly" "expected=${N_PD_RETRIABLE} actual=${CANCELED_RETRIABLE:-error}"
fi
CANCELED_HARD="$(q "SELECT count(*) FROM renewal_outbox o JOIN subscription s ON s.id = o.subscription_id JOIN customer c ON c.id = s.customer_id WHERE o.due_date = current_date AND s.status = 'canceled' AND ((c.payment_method = 'sdd' AND right(c.debtor_iban, 2) IN ('98','97')) OR (c.payment_method = 'card' AND right(c.card_token, 2) = '98'))")"
if [[ "$CANCELED_HARD" == "$N_PD_HARD" ]]; then
  pass "hard-fail family ended grace-expired-canceled exactly (${CANCELED_HARD})"
else
  fail "hard-fail family ended grace-expired-canceled exactly" "expected=${N_PD_HARD} actual=${CANCELED_HARD:-error}"
fi
CANCELED_DISPUTE="$(q "SELECT count(*) FROM renewal_outbox o JOIN subscription s ON s.id = o.subscription_id JOIN customer c ON c.id = s.customer_id WHERE o.due_date = current_date AND s.status = 'canceled' AND ((c.payment_method = 'sdd' AND right(c.debtor_iban, 2) = '96') OR (c.payment_method = 'card' AND right(c.card_token, 2) = '96'))")"
if [[ "$CANCELED_DISPUTE" == "$N_PD_DISPUTE" ]]; then
  pass "dispute family ended grace-expired-canceled exactly (${CANCELED_DISPUTE})"
else
  fail "dispute family ended grace-expired-canceled exactly" "expected=${N_PD_DISPUTE} actual=${CANCELED_DISPUTE:-error}"
fi

REMAINING_PAST_DUE="$(q "SELECT count(*) FROM renewal_outbox o JOIN subscription s ON s.id = o.subscription_id WHERE o.due_date = current_date AND s.status = 'past_due'")"
if [[ "$REMAINING_PAST_DUE" == "0" ]]; then
  pass "no due-cohort subscription remains past_due after enforcement"
else
  fail "no due-cohort subscription remains past_due after enforcement" "count=${REMAINING_PAST_DUE:-error}"
fi
CANCELED_WITH_GRACE="$(q "SELECT count(*) FROM subscription WHERE status = 'canceled' AND grace_until IS NOT NULL")"
if [[ "$CANCELED_WITH_GRACE" == "0" ]]; then
  pass "no canceled subscription retains a grace deadline"
else
  fail "no canceled subscription retains a grace deadline" "count=${CANCELED_WITH_GRACE:-error}"
fi

dunning_retriable_metric_delta_matches() {
  local current
  current="$(consumer_prom_sum '^dunning_transitions_total\{class="retriable"')"
  [[ "$current" =~ ^[0-9]+$ ]] \
    && (( current - M_DUNNING_RETRIABLE_BEFORE == N_PD_RETRIABLE + N_RETRY ))
}
dunning_hard_metric_delta_matches() {
  local current
  current="$(consumer_prom_sum '^dunning_transitions_total\{class="hard_fail"')"
  [[ "$current" =~ ^[0-9]+$ ]] \
    && (( current - M_DUNNING_HARD_BEFORE == N_PD_HARD ))
}
dunning_dispute_metric_delta_matches() {
  local current
  current="$(consumer_prom_sum '^dunning_transitions_total\{class="dispute"')"
  [[ "$current" =~ ^[0-9]+$ ]] \
    && (( current - M_DUNNING_DISPUTE_BEFORE == N_PD_DISPUTE ))
}
if [[ "$M_DUNNING_RETRIABLE_BEFORE" =~ ^[0-9]+$ ]]; then
  wait_for "dunning retriable transition delta matches its cohort exactly (${N_PD_RETRIABLE} + ${N_RETRY})" dunning_retriable_metric_delta_matches
else
  fail "dunning retriable transition delta matches its cohort exactly (${N_PD_RETRIABLE} + ${N_RETRY})" \
    "invalid baseline=${M_DUNNING_RETRIABLE_BEFORE}"
fi
if [[ "$M_DUNNING_HARD_BEFORE" =~ ^[0-9]+$ ]]; then
  wait_for "dunning hard-fail transition delta matches its cohort exactly (${N_PD_HARD})" dunning_hard_metric_delta_matches
else
  fail "dunning hard-fail transition delta matches its cohort exactly (${N_PD_HARD})" \
    "invalid baseline=${M_DUNNING_HARD_BEFORE}"
fi
if [[ "$M_DUNNING_DISPUTE_BEFORE" =~ ^[0-9]+$ ]]; then
  wait_for "dunning dispute transition delta matches its cohort exactly (${N_PD_DISPUTE})" dunning_dispute_metric_delta_matches
else
  fail "dunning dispute transition delta matches its cohort exactly (${N_PD_DISPUTE})" \
    "invalid baseline=${M_DUNNING_DISPUTE_BEFORE}"
fi

cancel_exhausted_metric_delta_matches() {
  local current
  current="$(consumer_prom_sum '^dunning_cancellations_total\{cause="exhausted"')"
  [[ "$current" =~ ^[0-9]+$ ]] \
    && (( current - M_CANCEL_EXHAUSTED_BEFORE == N_PD_RETRIABLE ))
}
cancel_expired_metric_delta_matches() {
  local current
  current="$(consumer_prom_sum '^dunning_cancellations_total\{cause="grace_expired"')"
  [[ "$current" =~ ^[0-9]+$ ]] \
    && (( current - M_CANCEL_EXPIRED_BEFORE == N_PD_HARD + N_PD_DISPUTE ))
}
if [[ "$M_CANCEL_EXHAUSTED_BEFORE" =~ ^[0-9]+$ ]]; then
  wait_for "exhausted cancellation delta matches the 99 family exactly (${N_PD_RETRIABLE})" cancel_exhausted_metric_delta_matches
else
  fail "exhausted cancellation delta matches the 99 family exactly (${N_PD_RETRIABLE})" \
    "invalid baseline=${M_CANCEL_EXHAUSTED_BEFORE}"
fi
if [[ "$M_CANCEL_EXPIRED_BEFORE" =~ ^[0-9]+$ ]]; then
  wait_for "grace-expired cancellation delta matches the hard-fail + dispute families exactly ($((N_PD_HARD + N_PD_DISPUTE)))" cancel_expired_metric_delta_matches
else
  fail "grace-expired cancellation delta matches the hard-fail + dispute families exactly ($((N_PD_HARD + N_PD_DISPUTE)))" \
    "invalid baseline=${M_CANCEL_EXPIRED_BEFORE}"
fi

# R26c bounds the 99 family: exactly DUNNING_MAX_ATTEMPTS total attempts each,
# every one failed with the class reason — the attempt count stops being
# run-length-shaped (R26b's deliberate non-assert) and becomes exact.
DUNNING_MAX_ATTEMPTS_VAL="$(env_val DUNNING_MAX_ATTEMPTS 3)"
EXPECTED_99_ROWS=$((N_PD_RETRIABLE * DUNNING_MAX_ATTEMPTS_VAL))
ATTEMPT_99_ROWS="$(q "SELECT count(*) FROM renewal_outbox o JOIN subscription s ON s.id = o.subscription_id JOIN customer c ON c.id = s.customer_id JOIN payment p ON (p.idempotency_key = 'sub-' || o.subscription_id || '|' || to_char(o.due_date, 'YYYY-MM-DD') OR p.idempotency_key LIKE 'sub-' || o.subscription_id || '|' || to_char(o.due_date, 'YYYY-MM-DD') || '|a%') WHERE o.due_date = current_date AND ((c.payment_method = 'sdd' AND right(c.debtor_iban, 2) = '99') OR (c.payment_method = 'card' AND right(c.card_token, 2) = '99'))")"
if [[ "$ATTEMPT_99_ROWS" == "$EXPECTED_99_ROWS" ]]; then
  pass "99-family attempts are bounded at DUNNING_MAX_ATTEMPTS exactly (${ATTEMPT_99_ROWS})"
else
  fail "99-family attempts are bounded at DUNNING_MAX_ATTEMPTS exactly" "expected=${EXPECTED_99_ROWS} actual=${ATTEMPT_99_ROWS:-error}"
fi
ATTEMPT_99_MISMATCH="$(q "SELECT count(*) FROM renewal_outbox o JOIN subscription s ON s.id = o.subscription_id JOIN customer c ON c.id = s.customer_id JOIN payment p ON (p.idempotency_key = 'sub-' || o.subscription_id || '|' || to_char(o.due_date, 'YYYY-MM-DD') OR p.idempotency_key LIKE 'sub-' || o.subscription_id || '|' || to_char(o.due_date, 'YYYY-MM-DD') || '|a%') WHERE o.due_date = current_date AND ((c.payment_method = 'sdd' AND right(c.debtor_iban, 2) = '99') OR (c.payment_method = 'card' AND right(c.card_token, 2) = '99')) AND NOT (p.status = 'failed' AND p.failure_reason = CASE WHEN c.payment_method = 'sdd' THEN 'AM04' ELSE 'insufficient_funds' END)")"
if [[ "$ATTEMPT_99_MISMATCH" == "0" ]]; then
  pass "every 99-family attempt failed with its class reason"
else
  fail "every 99-family attempt failed with its class reason" "count=${ATTEMPT_99_MISMATCH:-error}"
fi

# SDD-99 re-collections notify (failed) once each; card-99 re-collections
# decline synchronously and never notify; the 95 family adds its settled |a2
# rows — the |a-family inbox is exact, closing the run-length gap.
EXPECTED_RETRY_FAMILY_INBOX=$((N_SDD_99 * (DUNNING_MAX_ATTEMPTS_VAL - 1) + N_RETRY))
RETRY_FAMILY_INBOX="$(q "SELECT count(*) FROM settlement_inbox WHERE notification_id LIKE '%|a%'")"
if [[ "$RETRY_FAMILY_INBOX" == "$EXPECTED_RETRY_FAMILY_INBOX" ]]; then
  pass "retry-family inbox rows match the bounded prediction exactly (${RETRY_FAMILY_INBOX})"
else
  fail "retry-family inbox rows match the bounded prediction exactly" "expected=${EXPECTED_RETRY_FAMILY_INBOX} actual=${RETRY_FAMILY_INBOX:-error}"
fi

# R26c steady state: with every 99 exhausted and every grace enforced, the
# sweeper has nothing left to mint — the payment table must freeze across a
# full sweep interval (the interim-churn caveat, retired and made assertable).
DUNNING_SWEEP_INTERVAL_VAL="$(env_val DUNNING_SWEEP_INTERVAL_MS 10000)"
STEADY_PAYMENTS_BEFORE="$(q 'SELECT count(*) FROM payment')"
sleep $((DUNNING_SWEEP_INTERVAL_VAL / 1000 + 5))
STEADY_PAYMENTS_AFTER="$(q 'SELECT count(*) FROM payment')"
if [[ -n "$STEADY_PAYMENTS_BEFORE" && "$STEADY_PAYMENTS_BEFORE" == "$STEADY_PAYMENTS_AFTER" ]]; then
  pass "payment count frozen across a full sweep interval — dunning steady state (${STEADY_PAYMENTS_AFTER})"
else
  fail "payment count frozen across a full sweep interval — dunning steady state" "before=${STEADY_PAYMENTS_BEFORE:-error} after=${STEADY_PAYMENTS_AFTER:-error}"
fi

# Since R26b a failed attempt legitimately shares its charge with a later
# succeeded attempt, so the invariant is charge-shaped: finalization requires
# a succeeded payment, never failures alone.
FAILED_FINALIZED="$(q "SELECT count(*) FROM charge c WHERE c.status <> 'pending' AND NOT EXISTS (SELECT 1 FROM payment p WHERE p.charge_id = c.id AND p.status IN ('succeeded','charged_back'))")"
if [[ "$FAILED_FINALIZED" == "0" ]]; then
  pass "no charge finalized without a succeeded payment"
else
  fail "no charge finalized without a succeeded payment" "count=${FAILED_FINALIZED:-error}"
fi

FAILED_ADVANCED="$(q "SELECT count(*) FROM renewal_outbox o JOIN subscription s ON s.id = o.subscription_id JOIN customer c ON c.id = s.customer_id
WHERE o.due_date = current_date
  AND c.payment_method = 'card'
  AND right(c.card_token, 2) IN ('99','98')
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

wait_for "settlements main queue drained" settlements_main_empty
wait_for "settlements DLQ empty" settlements_dlq_empty

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
# Post-R26c the sweeper is provably quiescent at this point (exhaustion and
# expiry enforced and asserted above), so the snapshot covers every payment
# row — the re-trigger must mint nothing anywhere.
SNAP_SQL="SELECT (SELECT count(*) FROM renewal_outbox) || '|' || (SELECT count(*) FROM payment) || '|' || (SELECT count(*) FROM charge) || '|' || (SELECT count(*) FROM invoice)"
SNAP_BEFORE="$(q "$SNAP_SQL")"
trigger_job
assert_trigger "idempotency re-trigger"
if [[ "$TRIGGER_EXEC_ID" =~ ^[0-9]+$ ]]; then
  wait_for "idempotency re-trigger execution ${TRIGGER_EXEC_ID} reached COMPLETED" job_completed
else
  fail "idempotency re-trigger execution reached COMPLETED" "no executionId to poll"
fi
sleep 5   # settle window for any (unexpected) in-flight messages
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

  # The management API's message counter refreshes on a ~5s stats interval; poll
  # briefly instead of trusting a single read — the queue is typically already
  # empty when this check runs, but the counter may still show the
  # pre-dead-letter value (first observed at 100k scale during R12).
  POISON_MAIN_EMPTY=0
  POISON_MAIN_START=$SECONDS
  while (( SECONDS - POISON_MAIN_START < 30 )); do
    if [[ "$(queue_depth "$RMQ_QUEUE")" == "0" ]]; then
      POISON_MAIN_EMPTY=1
      break
    fi
    sleep 2
  done
  if (( POISON_MAIN_EMPTY )); then
    pass "main queue empty after poison message"
  else
    fail "main queue empty after poison message" "depth=$(queue_depth "$RMQ_QUEUE")"
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
  count="$(consumer_prom_sum '^spring_rabbitmq_listener_seconds_count')"
  [[ "$count" != "absent" && "$count" != "unreachable" ]]
}
wait_for "spring_rabbitmq_listener timer recorded on consumer" listener_timer_recorded

summary
