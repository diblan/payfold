#!/usr/bin/env bash
# Narrated chaos demo for a RUNNING Payfold stack.
#
# Scenes: pipeline, poison isolation, worker loss, scale-out, broker restart,
# chargebacks under slow/fast profiles, per-country slow-bank lag/drain,
# counterparty-amnesia recovery, and dunning recovery/cancellation.
#
# Usage:
#   scripts/chaos-demo.sh [--auto] [--timeout SECONDS]
#
#   --auto         skip the pause between scenes
#   --timeout N    max seconds to wait for each long condition (default 900 —
#                  on a fresh default-seed stack scene 1 bills the whole 15k
#                  base cohort at the measured post-D23 single-consumer rate
#                  (~195/s — the interim ~22/s regression is diagnosed and
#                  fixed, roadmap R36), plus the dunning re-collection tails;
#                  a pre-billed stack needs nothing near this)
#
# Environment:
#   DEMO_AUTO=1    skip the pause between scenes
#
# Requires: docker compose v2, curl. psql runs inside the postgres container.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

TIMEOUT=900
AUTO="${DEMO_AUTO:-0}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --auto) AUTO=1; shift ;;
    --timeout) TIMEOUT="${2:?--timeout needs a value}"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

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
CONSUMER_PORT_END="$(env_val CONSUMER_HTTP_PORT_END 8083)"
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
SETTLEMENT_DLQ="billing.settlements.dlq"

RESULTS=()
FAIL_COUNT=0
pass() { RESULTS+=("PASS  $1"); echo "[chaos-demo] PASS  $1"; }
fail() { RESULTS+=("FAIL  $1${2:+ — $2}"); echo "[chaos-demo] FAIL  $1${2:+ — $2}" >&2; ((FAIL_COUNT++)); }
note() { echo "[chaos-demo] $*"; }

q() { docker compose exec -T postgres psql -U "$PGUSER" -d "$PGDB" -Atc "$1" 2>/dev/null; }

# wait_until <description> <function> — polls every 2s up to $TIMEOUT
wait_until() {
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
  echo "================ chaos-demo summary ================"
  printf '%s\n' "${RESULTS[@]}"
  echo "===================================================="
  if (( FAIL_COUNT > 0 )); then
    echo "RESULT: FAIL (${FAIL_COUNT} failed)"
    exit 1
  fi
  echo "RESULT: PASS"
  exit 0
}

pause_between_scenes() {
  if [[ "$AUTO" != "1" ]]; then
    read -rp "  [enter] next scene…"
  fi
}

scene() {
  echo
  echo "━━━ Scene $1 — $2"
}

# queue name -> message count, or "unreachable"
queue_depth() {
  local body
  body="$(curl -fsS -u "${RMQ_USER}:${RMQ_PASS}" \
    "http://localhost:${RMQ_MGMT_PORT}/api/queues/%2F/$1" 2>/dev/null)" || { echo unreachable; return; }
  echo "$body" | grep -o '"messages":[0-9]*' | head -1 | cut -d: -f2
}

# port, extended-regex over metric lines -> integer sum | absent | unreachable
prom_val() {
  local body
  body="$(curl -fsS "http://localhost:$1/actuator/prometheus" 2>/dev/null)" || { echo unreachable; return; }
  echo "$body" | grep -E "$2" \
    | awk '{s+=$NF} END { if (NR==0) print "absent"; else printf "%.0f\n", s }'
}

# Sum an extended-regex metric over every responsive consumer replica port.
# Replicas expose per-process counters; the fleet-wide truth is their sum.
# Echoes the sum, "absent" if no responsive port serves the metric, or
# "unreachable" if no port in the range responds at all.
consumer_sum() {
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

producer_up() {
  curl -fsS "http://localhost:${PRODUCER_PORT}/actuator/health" 2>/dev/null \
    | grep -q '"status":"UP"'
}

consumer_up() {
  local port
  for port in $(seq "$CONSUMER_PORT" "$CONSUMER_PORT_END"); do
    if curl -fsS "http://localhost:${port}/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
      return 0
    fi
  done
  return 1
}

broker_up() {
  [[ "$(queue_depth "$RMQ_QUEUE")" != "unreachable" ]]
}

bank_up() {
  curl -fsS "http://localhost:${BANK_PORT}/health" 2>/dev/null \
    | grep -q '"status":"ok"'
}

bank_b_up() {
  curl -fsS "http://localhost:${BANK_B_PORT}/health" 2>/dev/null \
    | grep -q '"status":"ok"'
}

cardnet_up() {
  curl -fsS "http://localhost:${CARDNET_PORT}/health" 2>/dev/null \
    | grep -q '"status":"ok"'
}

outbox_drained() {
  [[ "$(q 'SELECT count(*) FROM renewal_outbox WHERE published_at IS NULL')" == "0" ]]
}

main_queue_empty() {
  [[ "$(queue_depth "$RMQ_QUEUE")" == "0" ]]
}

# Card terminality derives from each customer's stored token. The 95 cohort
# (R26b) terminates on its attempt-2 re-collection row (|a2 key): attempt 1
# fails by rule and the re-collection settles.
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

# SDD terminality includes the bank delay and chargeback lag. The bank
# attribution and ISO reason are part of the same per-row prediction; the 95
# cohort terminates on its attempt-2 re-collection row (|a2 key, R26b).
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
billed_ok() {
  [[ "$(q "$CARD_TERMINAL_MISMATCH_SQL")" == "0" \
    && "$(q "$SDD_TERMINAL_MISMATCH_SQL")" == "0" ]]
}

scene_billed_count() {
  q "SELECT count(*) FROM renewal_outbox o
JOIN subscription s ON s.id = o.subscription_id
JOIN customer c ON c.id = s.customer_id
JOIN payment p
  ON p.idempotency_key = 'sub-' || o.subscription_id || '|' || to_char(current_date, 'YYYY-MM-DD')
 AND p.status = 'succeeded'
WHERE o.due_date = current_date
  AND c.payment_method = 'card'
  AND c.email LIKE 'chaos-$1-${RUN_TAG}-%@example.test'"
}

TRIGGER_CODE=""
TRIGGER_TIME=""
TRIGGER_EXEC_ID=""

job_status() {
  local body
  body="$(curl -fsS "http://localhost:${PRODUCER_PORT}/actuator/renewal-job/$1" 2>/dev/null)" \
    || { echo unreachable; return; }
  echo "$body" | grep -o '"status":"[A-Z]*"' | head -1 | cut -d'"' -f4
}

job_completed() {
  [[ "$(job_status "$TRIGGER_EXEC_ID")" == "COMPLETED" ]]
}

trigger_and_wait() {
  local label="$1" body_file meta trigger_ok=1
  body_file="$(mktemp)"
  meta="$(curl -sS -o "$body_file" -w '%{http_code} %{time_total}' --max-time 30 \
    -X POST "http://localhost:${PRODUCER_PORT}/actuator/renewal-job?force=true" \
    -H 'Content-Type: application/vnd.spring-boot.actuator.v3+json' \
    -H 'Accept: application/json' -d '{}' 2>/dev/null)"
  TRIGGER_CODE="${meta%% *}"
  TRIGGER_TIME="${meta#* }"
  TRIGGER_EXEC_ID="$(grep -o '"executionId":[0-9]*' "$body_file" | head -1 | cut -d: -f2)"
  rm -f "$body_file"

  if [[ "$TRIGGER_CODE" == "200" ]]; then
    pass "${label} returned 200"
  else
    fail "${label} returned 200" "HTTP ${TRIGGER_CODE:-none}"
    trigger_ok=0
  fi
  if awk -v t="${TRIGGER_TIME:-999}" 'BEGIN { exit !(t < 1.0) }'; then
    pass "${label} returned in <1s (${TRIGGER_TIME}s)"
  else
    fail "${label} returned in <1s" "time_total=${TRIGGER_TIME:-unknown}s"
    trigger_ok=0
  fi
  if [[ "$TRIGGER_EXEC_ID" =~ ^[0-9]+$ ]]; then
    pass "${label} response contains executionId (${TRIGGER_EXEC_ID})"
  else
    fail "${label} response contains executionId" "no executionId in response body"
    trigger_ok=0
  fi
  (( trigger_ok )) || return 1
  wait_until "${label} execution ${TRIGGER_EXEC_ID} reached COMPLETED" job_completed
}

seed_due() {
  local n="$1" tag="$2" seed_out
  note "seeding ${n} extra due-today subscriptions (emails chaos-${tag}-${RUN_TAG}-<n>@example.test)…"
  if ! seed_out="$(docker compose exec -T postgres psql -U "$PGUSER" -d "$PGDB" -v ON_ERROR_STOP=1 <<SQL
WITH seed_plan AS (
    SELECT id, interval FROM plan
    WHERE interval = CASE WHEN (now() - interval '1 month') + interval '1 month' = now()
                          THEN 'month' ELSE 'year' END
    ORDER BY name LIMIT 1
), new_customers AS (
    -- Scenes 1-5 demonstrate delivery semantics with a clean card cohort.
    INSERT INTO customer (id, email, card_token)
    SELECT gen_random_uuid(),
           'chaos-${tag}-${RUN_TAG}-' || n || '@example.test',
           'tok-' || lpad(n::text, 10, '0') || '01'
    FROM generate_series(1, ${n}) n
    RETURNING id
)
INSERT INTO subscription (id, customer_id, plan_id, status, renewed_at)
SELECT gen_random_uuid(), c.id, (SELECT id FROM seed_plan), 'active',
       CASE WHEN (SELECT interval FROM seed_plan) = 'year'
            THEN now() - INTERVAL '1 year' ELSE now() - INTERVAL '1 month' END
FROM new_customers c;
ANALYZE customer;
ANALYZE subscription;
SQL
)"; then
    fail "scene ${tag#s} seeded ${n} due-today subscriptions" "psql failed: ${seed_out}"
    return 1
  fi
  if echo "$seed_out" | grep -q "INSERT 0 ${n}$"; then
    pass "scene ${tag#s} seeded ${n} due-today subscriptions"
    return 0
  fi
  fail "scene ${tag#s} seeded ${n} due-today subscriptions" "unexpected psql output: ${seed_out}"
  return 1
}

seed_chargeback_cohort() {
  local n="$1" seed_out
  note "seeding ${n} SDD chargeback subscriptions (emails chaos-6-${RUN_TAG}-<n>@example.test)…"
  if ! seed_out="$(docker compose exec -T postgres psql -U "$PGUSER" -d "$PGDB" -v ON_ERROR_STOP=1 <<SQL
WITH seed_plan AS (
    SELECT id, interval FROM plan
    WHERE interval = CASE WHEN (now() - interval '1 month') + interval '1 month' = now()
                          THEN 'month' ELSE 'year' END
    ORDER BY name LIMIT 1
), new_customers AS (
    INSERT INTO customer (
        id, email, name, payment_method, debtor_iban, mandate_reference, country
    )
    SELECT gen_random_uuid(),
           'chaos-6-${RUN_TAG}-' || n || '@example.test',
           'Chaos 6 Customer ' || n,
           'sdd',
           'BE68' || lpad(n::text, 10, '0') || '96',
           'MNDT-CHAOS-' || n,
           'BE'
    FROM generate_series(1, ${n}) n
    RETURNING id
)
INSERT INTO subscription (id, customer_id, plan_id, status, renewed_at)
SELECT gen_random_uuid(), c.id, (SELECT id FROM seed_plan), 'active',
       CASE WHEN (SELECT interval FROM seed_plan) = 'year'
            THEN now() - INTERVAL '1 year' ELSE now() - INTERVAL '1 month' END
FROM new_customers c;
ANALYZE customer;
ANALYZE subscription;
SQL
)"; then
    fail "scene 6 seeded ${n} SDD chargeback subscriptions" "psql failed: ${seed_out}"
    return 1
  fi
  if echo "$seed_out" | grep -q "INSERT 0 ${n}$"; then
    pass "scene 6 seeded ${n} SDD chargeback subscriptions"
    return 0
  fi
  fail "scene 6 seeded ${n} SDD chargeback subscriptions" \
    "unexpected psql output: ${seed_out}"
  return 1
}

seed_routed_cohort() {
  local bank="$1" country="$2" n="$3" seed_out scene_number="${ROUTED_SCENE:-7}"
  note "seeding ${n} clean SDD subscriptions for ${bank} (${country})…"
  if ! seed_out="$(docker compose exec -T postgres psql -U "$PGUSER" -d "$PGDB" -v ON_ERROR_STOP=1 <<SQL
WITH seed_plan AS (
    SELECT id, interval FROM plan
    WHERE interval = CASE WHEN (now() - interval '1 month') + interval '1 month' = now()
                          THEN 'month' ELSE 'year' END
    ORDER BY name LIMIT 1
), new_customers AS (
    INSERT INTO customer (
        id, email, name, payment_method, debtor_iban, mandate_reference, country
    )
    SELECT gen_random_uuid(),
           'chaos-${scene_number}-${bank}-${RUN_TAG}-' || n || '@example.test',
           'Chaos ${scene_number} ${bank} Customer ' || n,
           'sdd',
           '${country}00' || lpad(n::text, 12, '0') || '01',
           'MNDT-CHAOS-${scene_number}-${bank}-' || n,
           '${country}'
    FROM generate_series(1, ${n}) n
    RETURNING id
)
INSERT INTO subscription (id, customer_id, plan_id, status, renewed_at)
SELECT gen_random_uuid(), c.id, (SELECT id FROM seed_plan), 'active',
       CASE WHEN (SELECT interval FROM seed_plan) = 'year'
            THEN now() - INTERVAL '1 year' ELSE now() - INTERVAL '1 month' END
FROM new_customers c;
ANALYZE customer;
ANALYZE subscription;
SQL
)"; then
    fail "scene ${scene_number} seeded ${bank} cohort" "psql failed: ${seed_out}"
    return 1
  fi
  if echo "$seed_out" | grep -q "INSERT 0 ${n}$"; then
    pass "scene ${scene_number} seeded ${bank} cohort (${n} clean ${country} SDD renewals)"
    return 0
  fi
  fail "scene ${scene_number} seeded ${bank} cohort" "unexpected psql output: ${seed_out}"
  return 1
}

seed_dunning_cohort() {
  local suffix="$1" n="$2" seed_out
  note "seeding ${n} SDD suffix-${suffix} dunning subscriptions (emails chaos-9-${suffix}-${RUN_TAG}-<n>@example.test)…"
  if ! seed_out="$(docker compose exec -T postgres psql -U "$PGUSER" -d "$PGDB" -v ON_ERROR_STOP=1 <<SQL
WITH seed_plan AS (
    SELECT id, interval FROM plan
    WHERE interval = CASE WHEN (now() - interval '1 month') + interval '1 month' = now()
                          THEN 'month' ELSE 'year' END
    ORDER BY name LIMIT 1
), new_customers AS (
    INSERT INTO customer (
        id, email, name, payment_method, debtor_iban, mandate_reference, country
    )
    SELECT gen_random_uuid(),
           'chaos-9-${suffix}-${RUN_TAG}-' || n || '@example.test',
           'Chaos 9 Suffix ${suffix} Customer ' || n,
           'sdd',
           'BE68' || lpad(n::text, 10, '0') || '${suffix}',
           'MNDT-CHAOS-9-${suffix}-' || n,
           'BE'
    FROM generate_series(1, ${n}) n
    RETURNING id
)
INSERT INTO subscription (id, customer_id, plan_id, status, renewed_at)
SELECT gen_random_uuid(), c.id, (SELECT id FROM seed_plan), 'active',
       CASE WHEN (SELECT interval FROM seed_plan) = 'year'
            THEN now() - INTERVAL '1 year' ELSE now() - INTERVAL '1 month' END
FROM new_customers c;
ANALYZE customer;
ANALYZE subscription;
SQL
)"; then
    fail "scene 9 seeded suffix-${suffix} cohort" "psql failed: ${seed_out}"
    return 1
  fi
  if echo "$seed_out" | grep -q "INSERT 0 ${n}$"; then
    pass "scene 9 seeded suffix-${suffix} cohort (${n} SDD renewals)"
    return 0
  fi
  fail "scene 9 seeded suffix-${suffix} cohort" "unexpected psql output: ${seed_out}"
  return 1
}

responsive_consumer_count() {
  local count=0 port
  for port in $(seq "$CONSUMER_PORT" "$CONSUMER_PORT_END"); do
    if curl -fsS "http://localhost:${port}/actuator/prometheus" >/dev/null 2>&1; then
      ((count++))
    fi
  done
  echo "$count"
}

three_consumers_respond() {
  [[ "$(responsive_consumer_count)" -ge 3 ]] 2>/dev/null
}

PROC_REGEX='^renewals_processed_total\{.*outcome="(succeeded|failed|submitted)"'
RUN_TAG="$(date +%s)-$$"

echo "Payfold scripted chaos demo"
echo "Grafana: http://localhost:${GRAFANA_PORT}/d/payfold-pipeline (anonymous)"

wait_until "producer /actuator/health UP" producer_up || summary
wait_until "consumer /actuator/health UP" consumer_up || summary
wait_until "RabbitMQ management API reachable" broker_up || summary
wait_until "mock-bank /health ok" bank_up || summary
wait_until "mock-bank-b /health ok" bank_b_up || summary
wait_until "mock-card /health ok" cardnet_up || summary
wait_until "starting outbox fully published" outbox_drained || summary
wait_until "starting main queue empty" main_queue_empty || summary
wait_until "starting due-today rows exactly billed" billed_ok || summary

START_DLQ_DEPTH="$(queue_depth "$RMQ_DLQ")"
if [[ "$START_DLQ_DEPTH" == "0" ]]; then
  pass "starting DLQ empty"
else
  fail "starting DLQ empty" "depth=${START_DLQ_DEPTH}"
  summary
fi

scene 1 "The pipeline (G1: outbox → broker → idempotent billing)"
seed_due 5000 s1 || summary
SCENE_1_START=$SECONDS
trigger_and_wait "scene 1 renewal job trigger" || summary
wait_until "scene 1 outbox fully published" outbox_drained || summary
if wait_until "scene 1 every due renewal reached its predicted terminal payment" billed_ok; then
  SCENE_1_DRAIN_SECS=$(( SECONDS - SCENE_1_START ))
  pass "scene 1 pipeline drained in ${SCENE_1_DRAIN_SECS}s"
fi

pause_between_scenes

scene 2 "Poison while good traffic flows (G5: bounded attempts, no silent drop)"
seed_due 2000 s2 || summary
trigger_and_wait "scene 2 renewal job trigger" || summary
wait_until "scene 2 outbox fully published" outbox_drained || summary

# The management API's message counter refreshes on a ~5s stats interval, so a
# single read right after the publish can return a pre-publish 0 while the
# backlog is real (R27; the same race verify.sh's R17 fix polls around). Poll
# briefly for a nonzero sample instead of trusting the first read.
SCENE_2_DEPTH=0
SCENE_2_DEPTH_START=$SECONDS
while (( SECONDS - SCENE_2_DEPTH_START < 15 )); do
  SCENE_2_DEPTH="$(queue_depth "$RMQ_QUEUE")"
  if [[ "$SCENE_2_DEPTH" =~ ^[0-9]+$ ]] && (( SCENE_2_DEPTH > 0 )); then
    break
  fi
  sleep 1
done
if [[ "$SCENE_2_DEPTH" =~ ^[0-9]+$ ]] && (( SCENE_2_DEPTH > 0 )); then
  pass "scene 2 poison injected mid-drain with good-message backlog (${SCENE_2_DEPTH})"
else
  fail "scene 2 poison injected mid-drain with good-message backlog" "depth=${SCENE_2_DEPTH}"
fi

POISON_MARKER="chaos-poison-$(date +%s)-$$"
if ! PUBLISH_RESPONSE="$(curl -fsS -u "${RMQ_USER}:${RMQ_PASS}" \
  -X POST "http://localhost:${RMQ_MGMT_PORT}/api/exchanges/%2F/${RMQ_EXCHANGE}/publish" \
  -H 'Content-Type: application/json' \
  -d "{\"properties\":{},\"routing_key\":\"${RMQ_RK}\",\"payload\":\"${POISON_MARKER}\",\"payload_encoding\":\"string\"}" \
  2>/dev/null)"; then
  fail "scene 2 poison message published" "RabbitMQ management API unreachable on :${RMQ_MGMT_PORT}"
elif echo "$PUBLISH_RESPONSE" | grep -q '"routed":true'; then
  pass "scene 2 poison message published and routed"
else
  fail "scene 2 poison message published and routed" "response=${PUBLISH_RESPONSE:-empty}"
fi

# The poison sits behind the scene's FIFO backlog, so its DLQ deadline is
# queue wait + the bounded listener retry envelope: ~2000 messages drain in
# well under a minute at the measured post-D23 ~195/s single-consumer rate
# (R36), plus retry backoff and the stats interval — 150s stays as a
# generous ceiling. G5's bound is on attempts once delivered, not on queue
# position.
POISON_DLQ_READY=0
POISON_WAIT_START=$SECONDS
while (( SECONDS - POISON_WAIT_START < 150 )); do
  if [[ "$(queue_depth "$RMQ_DLQ")" == "1" ]]; then
    POISON_DLQ_READY=1
    break
  fi
  sleep 2
done
if (( POISON_DLQ_READY )); then
  pass "scene 2 poison message reached DLQ within 150s"
else
  fail "scene 2 poison message reached DLQ within 150s" \
    "if the broker carries pre-R5 queue args, wipe the RabbitMQ volume (docker compose down -v) so the queue is redeclared"
fi

if ! DRAIN_RESPONSE="$(curl -fsS -u "${RMQ_USER}:${RMQ_PASS}" \
  -X POST "http://localhost:${RMQ_MGMT_PORT}/api/queues/%2F/${RMQ_DLQ}/get" \
  -H 'Content-Type: application/json' \
  -d '{"count":5,"ackmode":"ack_requeue_false","encoding":"auto"}' \
  2>/dev/null)"; then
  fail "scene 2 poison message drained from DLQ" "RabbitMQ management API unreachable on :${RMQ_MGMT_PORT}"
elif echo "$DRAIN_RESPONSE" | grep -Fq "$POISON_MARKER"; then
  pass "scene 2 poison message drained from DLQ and marker matched"
else
  fail "scene 2 poison message drained from DLQ and marker matched" "marker not found in response"
fi

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
  pass "scene 2 DLQ empty after poison probe"
else
  fail "scene 2 DLQ empty after poison probe" "depth=$(queue_depth "$RMQ_DLQ")"
fi

wait_until "scene 2 good traffic completed despite poison" billed_ok

pause_between_scenes

scene 3 "Kill the worker (G1/G2: worker dies → nothing lost → backlog drains on recovery; auto-respawn is orchestration and out of scope)"
# Settlement webhooks fired during the deliberate outage survive on the
# counterparties' bounded retry envelope (BANK_WEBHOOK_RETRY_*, ~4 min — R23f).
# Never restart a counterparty to reprofile it mid-scene: pending deliveries
# are in-memory and die with the container (the R28 amnesia note).
seed_due 5000 s3 || summary
SCENE_3_PROC_BEFORE="$(consumer_sum "$PROC_REGEX")"
if [[ "$SCENE_3_PROC_BEFORE" =~ ^[0-9]+$ ]]; then
  pass "scene 3 fleet processed-counter baseline captured (${SCENE_3_PROC_BEFORE})"
else
  fail "scene 3 fleet processed-counter baseline captured" "value=${SCENE_3_PROC_BEFORE}"
  summary
fi

trigger_and_wait "scene 3 renewal job trigger" || summary
wait_until "scene 3 outbox fully published" outbox_drained || summary

scene_3_consumed_1500() {
  local current
  current="$(consumer_sum "$PROC_REGEX")"
  [[ "$current" =~ ^[0-9]+$ ]] && (( current - SCENE_3_PROC_BEFORE >= 1500 ))
}
wait_until "scene 3 fleet consumed at least 1500 messages" scene_3_consumed_1500 || summary

if docker compose kill renewal-consumer; then
  pass "scene 3 renewal-consumer killed mid-drain"
else
  fail "scene 3 renewal-consumer killed mid-drain" "docker compose kill failed"
  summary
fi

sleep 10
DEATH_PROC="$(consumer_sum "$PROC_REGEX")"
DEATH_DEPTH="$(queue_depth "$RMQ_QUEUE")"
DEATH_PROVED=0
if [[ "$DEATH_PROC" == "unreachable" || "$DEATH_PROC" == "absent" ]] \
  && [[ "$DEATH_DEPTH" =~ ^[0-9]+$ ]] && (( DEATH_DEPTH > 0 )); then
  DEATH_PROVED=1
  pass "scene 3 worker death proved: fleet counter unreadable and broker backlog parked (${DEATH_DEPTH})"
else
  DEATH_BILLED_BEFORE="$(scene_billed_count s3)"
  DEATH_DEPTH_BEFORE="$(queue_depth "$RMQ_QUEUE")"
  sleep 8
  DEATH_BILLED_AFTER="$(scene_billed_count s3)"
  DEATH_DEPTH_AFTER="$(queue_depth "$RMQ_QUEUE")"
  if [[ "$DEATH_BILLED_BEFORE" =~ ^[0-9]+$ && "$DEATH_BILLED_AFTER" == "$DEATH_BILLED_BEFORE" \
    && "$DEATH_DEPTH_BEFORE" =~ ^[0-9]+$ && "$DEATH_DEPTH_AFTER" =~ ^[0-9]+$ ]] \
    && (( DEATH_DEPTH_BEFORE > 0 && DEATH_DEPTH_AFTER > 0 )); then
    DEATH_PROVED=1
    pass "scene 3 worker death proved: billed count frozen at ${DEATH_BILLED_AFTER} across 8s with broker backlog parked"
  fi
fi
if (( ! DEATH_PROVED )); then
  fail "scene 3 worker death and parked backlog proved" \
    "counter=${DEATH_PROC} depth=${DEATH_DEPTH} billed=${DEATH_BILLED_BEFORE:-unread}/${DEATH_BILLED_AFTER:-unread}"
fi

if docker compose up -d --no-deps renewal-consumer; then
  pass "scene 3 renewal-consumer restarted"
else
  fail "scene 3 renewal-consumer restarted" "docker compose up failed"
  summary
fi
wait_until "scene 3 recovered consumer UP" consumer_up || summary
wait_until "scene 3 broker backlog drained after recovery" main_queue_empty
if wait_until "scene 3 every due renewal reached its predicted terminal payment" billed_ok; then
  SCENE_3_BILLED="$(scene_billed_count s3)"
  if [[ "$SCENE_3_BILLED" == "5000" ]]; then
    pass "scene 3 backlog survived and drained on recovery, exactly once in effect (5000/5000)"
  else
    fail "scene 3 backlog survived and drained on recovery, exactly once in effect" \
      "scene rows at predicted terminal status=${SCENE_3_BILLED:-error}/5000"
  fi
fi

pause_between_scenes

scene 4 "Scale out (G2: competing consumers are safe by constraint-based idempotency)"
if docker compose up -d --no-deps --scale renewal-consumer=3 renewal-consumer; then
  pass "scene 4 requested three renewal-consumer replicas without recreating other services"
else
  fail "scene 4 requested three renewal-consumer replicas" "docker compose up --scale failed"
  summary
fi
wait_until "scene 4 at least two more replica ports respond" three_consumers_respond || summary

declare -A SCENE_4_BEFORE
SCENE_4_PORTS=()
for port in $(seq "$CONSUMER_PORT" "$CONSUMER_PORT_END"); do
  value="$(prom_val "$port" "$PROC_REGEX")"
  [[ "$value" == "unreachable" ]] && continue
  [[ "$value" == "absent" ]] && value=0
  if [[ "$value" =~ ^[0-9]+$ ]]; then
    SCENE_4_PORTS+=("$port")
    SCENE_4_BEFORE["$port"]="$value"
  else
    fail "scene 4 processed-counter baseline captured on :${port}" "value=${value}"
  fi
done
if [[ "${#SCENE_4_PORTS[@]}" -ge 3 ]]; then
  pass "scene 4 per-port processed-counter baselines captured (${SCENE_4_PORTS[*]})"
else
  fail "scene 4 per-port processed-counter baselines captured" "responsive ports=${#SCENE_4_PORTS[@]}"
  summary
fi

seed_due 6000 s4 || summary
trigger_and_wait "scene 4 renewal job trigger" || summary
wait_until "scene 4 outbox fully published" outbox_drained
wait_until "scene 4 every due renewal reached its predicted terminal payment" billed_ok

SCENE_4_ALL_INCREASED=1
SCENE_4_DETAIL=""
for port in "${SCENE_4_PORTS[@]}"; do
  before="${SCENE_4_BEFORE[$port]}"
  after="$(prom_val "$port" "$PROC_REGEX")"
  SCENE_4_DETAIL+="${port}:${before}→${after} "
  if ! [[ "$after" =~ ^[0-9]+$ ]] || (( after <= before )); then
    SCENE_4_ALL_INCREASED=0
  fi
done
if (( SCENE_4_ALL_INCREASED )); then
  pass "scene 4 every responsive replica processed messages (${SCENE_4_DETAIL}); measured 2026-07-26: 3 same-host replicas ≈2× drain, and 33,335/33,320/33,345 split at 100k; in-process concurrency ×8 ≈10×"
else
  fail "scene 4 every responsive replica processed messages" "${SCENE_4_DETAIL}"
fi

pause_between_scenes

scene 5 "Broker restart (G1/G2: durable queues + reconnect + redelivery absorbed)"
# One worker for this scene: at scene 4's three-replica drain rate the backlog
# empties before the restart can interrupt it, and the story here is a parked
# backlog surviving the broker — scale down first so the queue stays deep.
if docker compose up -d --no-deps --scale renewal-consumer=1 renewal-consumer >/dev/null 2>&1; then
  pass "scene 5 scaled back to a single consumer so the backlog outlives the drain"
else
  fail "scene 5 scaled back to a single consumer" "docker compose up --scale failed"
  summary
fi
wait_until "scene 5 single consumer replica UP" consumer_up || summary
seed_due 3000 s5 || summary
SCENE_5_PROC_BEFORE="$(consumer_sum "$PROC_REGEX")"
if [[ "$SCENE_5_PROC_BEFORE" =~ ^[0-9]+$ ]]; then
  pass "scene 5 fleet processed-counter baseline captured (${SCENE_5_PROC_BEFORE})"
else
  fail "scene 5 fleet processed-counter baseline captured" "value=${SCENE_5_PROC_BEFORE}"
  summary
fi

trigger_and_wait "scene 5 renewal job trigger" || summary
wait_until "scene 5 outbox fully published" outbox_drained || summary

scene_5_consumed_800() {
  local current
  current="$(consumer_sum "$PROC_REGEX")"
  [[ "$current" =~ ^[0-9]+$ ]] && (( current - SCENE_5_PROC_BEFORE >= 800 ))
}
wait_until "scene 5 fleet consumed at least 800 messages" scene_5_consumed_800 || summary

# The management counter refreshes on a ~5s stats interval — poll briefly for a
# nonzero read instead of trusting one sample (the single-consumer drain rate
# keeps thousands parked here, so a zero can only be a stale counter).
SCENE_5_DEPTH=0
SCENE_5_DEPTH_START=$SECONDS
while (( SECONDS - SCENE_5_DEPTH_START < 20 )); do
  SCENE_5_DEPTH="$(queue_depth "$RMQ_QUEUE")"
  [[ "$SCENE_5_DEPTH" =~ ^[0-9]+$ ]] && (( SCENE_5_DEPTH > 0 )) && break
  sleep 2
done
if [[ "$SCENE_5_DEPTH" =~ ^[0-9]+$ ]] && (( SCENE_5_DEPTH > 0 )); then
  pass "scene 5 broker has parked messages before restart (${SCENE_5_DEPTH})"
else
  fail "scene 5 broker has parked messages before restart" "depth=${SCENE_5_DEPTH}"
fi

if docker compose restart rabbitmq; then
  pass "scene 5 RabbitMQ restarted mid-drain"
else
  fail "scene 5 RabbitMQ restarted mid-drain" "docker compose restart failed"
  summary
fi
wait_until "scene 5 RabbitMQ management API healthy again" broker_up || summary
wait_until "scene 5 durable broker backlog drained after restart" main_queue_empty
if wait_until "scene 5 every due renewal reached its predicted terminal payment" billed_ok; then
  SCENE_5_BILLED="$(scene_billed_count s5)"
  if [[ "$SCENE_5_BILLED" == "3000" ]]; then
    pass "scene 5 parked messages survived, consumers reconnected, and redelivery was absorbed without double billing (3000/3000)"
  else
    fail "scene 5 parked messages survived, consumers reconnected, and redelivery was absorbed without double billing" \
      "scene rows at predicted terminal status=${SCENE_5_BILLED:-error}/3000"
  fi
fi

pause_between_scenes

scene 6 "Chargebacks are recorded facts; chaos profiles shift the picture"
note "Watch the dashboard's processed-by-outcome panel: the slow bank parks a visible submitted plateau before MD06 chargebacks land."
SCENE_6_SIZE=40
seed_chargeback_cohort "$SCENE_6_SIZE" || summary
if BANK_SETTLEMENT_DELAY_SECONDS=25 docker compose up -d mock-bank; then
  pass "scene 6 restarted mock-bank with a 25s settlement delay"
else
  fail "scene 6 restarted mock-bank with a 25s settlement delay" "docker compose up failed"
  summary
fi
wait_until "scene 6 slow mock-bank healthcheck passed" bank_up || summary
trigger_and_wait "scene 6 renewal job trigger" || summary
wait_until "scene 6 outbox fully published" outbox_drained || summary

scene_6_payment_count() {
  q "SELECT count(*) FROM payment p
JOIN charge ch ON ch.id = p.charge_id
JOIN subscription s ON s.id = ch.subscription_id
JOIN customer c ON c.id = s.customer_id
WHERE c.email LIKE 'chaos-6-${RUN_TAG}-%@example.test'
  AND p.status = '$1'"
}
scene_6_all_submitted() {
  [[ "$(scene_6_payment_count submitted)" == "$SCENE_6_SIZE" ]]
}
wait_until "scene 6 all ${SCENE_6_SIZE} payments visibly parked submitted" scene_6_all_submitted || summary

SCENE_6_HOLD_OK=1
SCENE_6_HOLD_START=$SECONDS
while (( SECONDS - SCENE_6_HOLD_START < 6 )); do
  SCENE_6_SUBMITTED="$(scene_6_payment_count submitted)"
  if ! [[ "$SCENE_6_SUBMITTED" =~ ^[0-9]+$ ]] || (( SCENE_6_SUBMITTED == 0 )); then
    SCENE_6_HOLD_OK=0
    break
  fi
  sleep 2
done
if (( SCENE_6_HOLD_OK )); then
  pass "scene 6 submitted backlog remained visible for 6s"
else
  fail "scene 6 submitted backlog remained visible for 6s" \
    "submitted=${SCENE_6_SUBMITTED:-unreadable}"
fi

scene_6_lifecycle_complete() {
  [[ "$(q "SELECT count(*) FROM payment p
JOIN charge ch ON ch.id = p.charge_id
JOIN invoice i ON i.id = ch.invoice_id
JOIN subscription s ON s.id = ch.subscription_id
JOIN customer c ON c.id = s.customer_id
WHERE c.email LIKE 'chaos-6-${RUN_TAG}-%@example.test'
  AND p.status = 'charged_back'
  AND p.failure_reason = 'MD06'
  AND p.charged_back_at IS NOT NULL
  AND ch.status = 'settled'
  AND i.status = 'disputed'
  AND s.renewed_at >= current_date")" == "$SCENE_6_SIZE" ]]
}
wait_until "scene 6 all ${SCENE_6_SIZE} payments completed settled → charged_back after the slow-bank delay + chargeback lag" scene_6_lifecycle_complete

if docker compose up -d mock-bank; then
  pass "scene 6 restored the fast mock-bank profile"
else
  fail "scene 6 restored the fast mock-bank profile" "docker compose up failed"
fi
wait_until "scene 6 restored mock-bank healthcheck passed" bank_up

if scene_6_lifecycle_complete; then
  pass "scene 6 every chargeback left its invoice disputed, charge settled, and subscription advanced"
else
  fail "scene 6 every chargeback left its invoice disputed, charge settled, and subscription advanced"
fi
SCENE_6_FAILED="$(scene_6_payment_count failed)"
if [[ "$SCENE_6_FAILED" == "0" ]]; then
  pass "scene 6 zero payments failed"
else
  fail "scene 6 zero payments failed" "count=${SCENE_6_FAILED:-error}"
fi
SCENE_6_PAID="$(q "SELECT count(*) FROM invoice i
JOIN customer c ON c.id = i.customer_id
WHERE c.email LIKE 'chaos-6-${RUN_TAG}-%@example.test'
  AND i.status = 'paid'")"
if [[ "$SCENE_6_PAID" == "0" ]]; then
  pass "scene 6 zero invoices remained paid after chargeback"
else
  fail "scene 6 zero invoices remained paid after chargeback" \
    "count=${SCENE_6_PAID:-error}"
fi
settlements_dlq_empty() {
  [[ "$(queue_depth "$SETTLEMENT_DLQ")" == "0" ]]
}
wait_until "scene 6 settlements DLQ empty" settlements_dlq_empty

pause_between_scenes

scene 7 "Country routing makes the slow bank lag, then its backlog drains"
note "Watch the per-bank settlement latency and outcome panels: bank-a (BE) clears before deliberately slow bank-b (NL)."
SCENE_7_SIZE=20
ROUTED_SCENE=7
seed_routed_cohort bank-a BE "$SCENE_7_SIZE" || summary
seed_routed_cohort bank-b NL "$SCENE_7_SIZE" || summary
trigger_and_wait "scene 7 renewal job trigger" || summary
wait_until "scene 7 outbox fully published" outbox_drained || summary

scene_7_terminal_count() {
  q "SELECT count(*) FROM payment p
JOIN charge ch ON ch.id = p.charge_id
JOIN subscription s ON s.id = ch.subscription_id
JOIN customer c ON c.id = s.customer_id
WHERE c.email LIKE 'chaos-7-$1-${RUN_TAG}-%@example.test'
  AND p.bank_id = '$1'
  AND p.status IN ('succeeded','failed','charged_back')"
}
scene_7_submitted_count() {
  q "SELECT count(*) FROM payment p
JOIN charge ch ON ch.id = p.charge_id
JOIN subscription s ON s.id = ch.subscription_id
JOIN customer c ON c.id = s.customer_id
WHERE c.email LIKE 'chaos-7-$1-${RUN_TAG}-%@example.test'
  AND p.bank_id = '$1'
  AND p.status = 'submitted'"
}
scene_7_fast_done_slow_lagging() {
  local fast_terminal slow_submitted
  fast_terminal="$(scene_7_terminal_count bank-a)"
  slow_submitted="$(scene_7_submitted_count bank-b)"
  [[ "$fast_terminal" == "$SCENE_7_SIZE"
    && "$slow_submitted" =~ ^[0-9]+$
    && "$slow_submitted" -ge 1 ]]
}
wait_until "scene 7 bank-a cohort fully terminal while bank-b still has a submitted backlog" scene_7_fast_done_slow_lagging || summary

scene_7_slow_drained() {
  [[ "$(scene_7_terminal_count bank-b)" == "$SCENE_7_SIZE"
    && "$(scene_7_submitted_count bank-b)" == "0" ]]
}
wait_until "scene 7 bank-b submitted backlog drained fully terminal" scene_7_slow_drained

pause_between_scenes

scene 8 "Counterparty amnesia (D18/R28): a recreated bank forgets, the sweeper re-queries and resubmits — nothing stays stranded"
note "Watch the recovery panel: the point is pull-shaped recovery — no resend endpoint, no manual intervention."
SCENE_8_SIZE=20
ROUTED_SCENE=8
seed_routed_cohort bank-b NL "$SCENE_8_SIZE" || summary
trigger_and_wait "scene 8 renewal job trigger" || summary
wait_until "scene 8 outbox fully published" outbox_drained || summary

scene_8_payment_count() {
  q "SELECT count(*) FROM payment p
JOIN charge ch ON ch.id = p.charge_id
JOIN subscription s ON s.id = ch.subscription_id
JOIN customer c ON c.id = s.customer_id
WHERE c.email LIKE 'chaos-8-bank-b-${RUN_TAG}-%@example.test'
  AND p.bank_id = 'bank-b'
  AND p.status = '$1'"
}
scene_8_all_submitted() {
  [[ "$(scene_8_payment_count submitted)" == "$SCENE_8_SIZE" ]]
}
wait_until "scene 8 all ${SCENE_8_SIZE} payments visibly parked submitted" scene_8_all_submitted || summary

SCENE_8_RESUBMITTED_BEFORE="$(consumer_sum '^recovery_sweeps_total\{.*result="resubmitted"')"
if [[ "$SCENE_8_RESUBMITTED_BEFORE" =~ ^[0-9]+$ ]]; then
  pass "scene 8 fleet resubmitted-counter baseline captured (${SCENE_8_RESUBMITTED_BEFORE})"
else
  fail "scene 8 fleet resubmitted-counter baseline captured" "value=${SCENE_8_RESUBMITTED_BEFORE}"
  summary
fi

if docker compose up -d --no-deps --force-recreate mock-bank-b; then
  pass "scene 8 recreated mock-bank-b and erased its in-memory collection state"
else
  fail "scene 8 recreated mock-bank-b" "docker compose up failed"
  summary
fi
wait_until "scene 8 recreated mock-bank-b healthcheck passed" bank_b_up || summary

# Recovery budget: stale 30s + sweep tick 10s + resubmitted settlement 8s is
# approximately under a minute in the compose demo profile.
scene_8_recovered() {
  [[ "$(scene_8_payment_count succeeded)" == "$SCENE_8_SIZE"
    && "$(scene_8_payment_count submitted)" == "0" ]]
}
wait_until "scene 8 full cohort succeeded and zero submitted remain" scene_8_recovered

SCENE_8_RESUBMITTED_AFTER="$(consumer_sum '^recovery_sweeps_total\{.*result="resubmitted"')"
if [[ "$SCENE_8_RESUBMITTED_AFTER" =~ ^[0-9]+$ ]] \
  && (( SCENE_8_RESUBMITTED_AFTER - SCENE_8_RESUBMITTED_BEFORE >= 1 )); then
  pass "scene 8 recovery visibly resubmitted at least one forgotten collection"
else
  fail "scene 8 recovery visibly resubmitted at least one forgotten collection" \
    "before=${SCENE_8_RESUBMITTED_BEFORE} after=${SCENE_8_RESUBMITTED_AFTER}"
fi

pause_between_scenes

scene 9 "Dunning (D16/R26): a retriable failure recovers on re-collection; bounded exhaustion cancels"
note "Watch the past-due gauge rise and fall, and the cancellations-by-cause panel tick when the 99 cohort exhausts."
SCENE_9_SIZE=20
DUNNING_MAX="$(env_val DUNNING_MAX_ATTEMPTS 3)"
seed_dunning_cohort 95 "$SCENE_9_SIZE" || summary
seed_dunning_cohort 99 "$SCENE_9_SIZE" || summary

SCENE_9_EXHAUSTED_BEFORE="$(consumer_sum '^dunning_cancellations_total\{cause="exhausted"')"
if [[ "$SCENE_9_EXHAUSTED_BEFORE" =~ ^[0-9]+$ ]]; then
  pass "scene 9 exhausted-cancellations baseline captured (${SCENE_9_EXHAUSTED_BEFORE})"
else
  fail "scene 9 exhausted-cancellations baseline captured" "value=${SCENE_9_EXHAUSTED_BEFORE}"
  summary
fi

trigger_and_wait "scene 9 renewal job trigger" || summary
wait_until "scene 9 outbox fully published" outbox_drained || summary

# The failed collections cluster within a few seconds while the earliest
# re-collection waits out DUNNING_RETRY_DELAY_SECONDS, so the whole cohort is
# simultaneously past_due for a comfortably pollable window.
scene_9_95_past_due() {
  [[ "$(q "SELECT count(*) FROM subscription s
JOIN customer c ON c.id = s.customer_id
WHERE c.email LIKE 'chaos-9-95-${RUN_TAG}-%@example.test'
  AND s.status = 'past_due'")" == "$SCENE_9_SIZE" ]]
}
wait_until "scene 9 the whole 95 cohort visibly failed into past_due grace (${SCENE_9_SIZE})" scene_9_95_past_due || summary

scene_9_95_recovered() {
  [[ "$(q "SELECT count(*) FROM subscription s
JOIN customer c ON c.id = s.customer_id
JOIN charge ch ON ch.subscription_id = s.id
JOIN payment p ON p.charge_id = ch.id AND p.attempt = 2
WHERE c.email LIKE 'chaos-9-95-${RUN_TAG}-%@example.test'
  AND s.status = 'active' AND s.grace_until IS NULL
  AND p.status = 'succeeded'")" == "$SCENE_9_SIZE" ]]
}
wait_until "scene 9 the 95 cohort recovered to active on its settled re-collection (${SCENE_9_SIZE})" scene_9_95_recovered

scene_9_99_canceled() {
  [[ "$(q "SELECT count(*) FROM subscription s
JOIN customer c ON c.id = s.customer_id
WHERE c.email LIKE 'chaos-9-99-${RUN_TAG}-%@example.test'
  AND s.status = 'canceled' AND s.grace_until IS NULL")" == "$SCENE_9_SIZE" ]]
}
wait_until "scene 9 the 99 cohort exhausted into cancellation with grace cleared (${SCENE_9_SIZE})" scene_9_99_canceled

SCENE_9_99_ROWS_SQL="SELECT count(*) FROM payment p
JOIN charge ch ON ch.id = p.charge_id
JOIN subscription s ON s.id = ch.subscription_id
JOIN customer c ON c.id = s.customer_id
WHERE c.email LIKE 'chaos-9-99-${RUN_TAG}-%@example.test'"
SCENE_9_99_ATTEMPTS="$(q "${SCENE_9_99_ROWS_SQL}
  AND p.status = 'failed' AND p.failure_reason = 'AM04'")"
if [[ "$SCENE_9_99_ATTEMPTS" == "$((SCENE_9_SIZE * DUNNING_MAX))" ]]; then
  pass "scene 9 every 99 renewal made exactly DUNNING_MAX_ATTEMPTS failed AM04 attempts (${SCENE_9_99_ATTEMPTS})"
else
  fail "scene 9 every 99 renewal made exactly DUNNING_MAX_ATTEMPTS failed AM04 attempts" \
    "expected=$((SCENE_9_SIZE * DUNNING_MAX)) actual=${SCENE_9_99_ATTEMPTS:-error}"
fi

scene_9_exhausted_delta() {
  local current
  current="$(consumer_sum '^dunning_cancellations_total\{cause="exhausted"')"
  [[ "$current" =~ ^[0-9]+$ ]] \
    && (( current - SCENE_9_EXHAUSTED_BEFORE == SCENE_9_SIZE ))
}
wait_until "scene 9 exhausted-cancellations counter ticked exactly ${SCENE_9_SIZE}" scene_9_exhausted_delta

# Canceled is terminal: across a full sweep interval no further collection may
# be attempted for the exhausted cohort.
DUNNING_SWEEP_MS="$(env_val DUNNING_SWEEP_INTERVAL_MS 10000)"
SCENE_9_ROWS_BEFORE_HOLD="$(q "$SCENE_9_99_ROWS_SQL")"
sleep $((DUNNING_SWEEP_MS / 1000 + 5))
SCENE_9_ROWS_AFTER_HOLD="$(q "$SCENE_9_99_ROWS_SQL")"
if [[ -n "$SCENE_9_ROWS_BEFORE_HOLD" && "$SCENE_9_ROWS_BEFORE_HOLD" == "$SCENE_9_ROWS_AFTER_HOLD" ]]; then
  pass "scene 9 canceled subscriptions were never re-collected across a full sweep interval (${SCENE_9_ROWS_AFTER_HOLD} rows)"
else
  fail "scene 9 canceled subscriptions were never re-collected across a full sweep interval" \
    "before=${SCENE_9_ROWS_BEFORE_HOLD:-error} after=${SCENE_9_ROWS_AFTER_HOLD:-error}"
fi

echo
note "epilogue: returning the stack to one renewal-consumer replica…"
if docker compose up -d --no-deps --scale renewal-consumer=1 renewal-consumer; then
  pass "epilogue restored the default one-consumer shape"
else
  fail "epilogue restored the default one-consumer shape" "docker compose up --scale failed"
fi

summary
