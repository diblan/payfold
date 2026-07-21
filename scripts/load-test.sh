#!/usr/bin/env bash
# Load-test harness for a RUNNING Payfold stack. Measures, does not verify —
# scripts/verify.sh remains the definition of "working".
#
# Seeds N extra due-today subscriptions straight into Postgres (customers get a
# run-unique load-<epoch>-<pid>-<n>@example.test address, so re-runs never collide
# with the compose seeder or each other), triggers the async renewal job, polls the
# execution to a terminal status, and reports:
#   - job wall duration (execution startTime/endTime; host-side polling cross-check)
#   - outbox_inserted_total / outbox_published_total deltas (Prometheus), with the
#     published delta cross-checked against the database
#   - publish rate (published delta / wall duration)
#   - peak producer heap: max over 5s samples of sum(jvm_memory_used_bytes{area="heap"})
#
# Durations come from wall-clock timestamps, never JVM monotonic timers: on WSL2,
# nanoTime-backed timers run ~10% fast vs wall clock (docs/quality.md, R11 run).
# Counters are process-lifetime, so only same-run deltas are reported.
#
# The consumer keeps draining the queue after the job completes; this script does
# not wait for the drain. Watch renewals_processed_total on the consumer's
# /actuator/prometheus for drain progress.
#
# Usage: scripts/load-test.sh N
#
# Requires: docker compose v2, curl. psql runs inside the postgres container.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

N="${1:?usage: scripts/load-test.sh N}"
if ! [[ "$N" =~ ^[1-9][0-9]*$ ]]; then
  echo "[load-test] N must be a positive integer, got: ${N}" >&2
  exit 2
fi

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

note() { echo "[load-test] $*"; }
die()  { echo "[load-test] ERROR: $*" >&2; exit 1; }

q() { docker compose exec -T postgres psql -U "$PGUSER" -d "$PGDB" -Atc "$1" 2>/dev/null; }

prom_val() { # extended-regex over producer metric lines -> integer sum | absent | unreachable
  local body
  body="$(curl -fsS "http://localhost:${PRODUCER_PORT}/actuator/prometheus" 2>/dev/null)" || { echo unreachable; return; }
  echo "$body" | grep -E "$1" \
    | awk '{s+=$NF} END { if (NR==0) print "absent"; else printf "%.0f\n", s }'
}

[[ "$(q 'SELECT 1')" == "1" ]] || die "postgres unreachable — is the stack up? (docker compose up -d --build)"
curl -fsS "http://localhost:${PRODUCER_PORT}/actuator/health" 2>/dev/null | grep -q '"status":"UP"' \
  || die "producer not UP on :${PRODUCER_PORT}"

# --- seed N extra due-today subscriptions ------------------------------------

RUN_TAG="$(date +%s)-$$"
note "seeding ${N} extra due-today subscriptions (emails load-${RUN_TAG}-<n>@example.test)…"
SEED_START=$SECONDS
SEED_OUT="$(docker compose exec -T postgres psql -U "$PGUSER" -d "$PGDB" -v ON_ERROR_STOP=1 <<SQL
WITH monthly_plan AS (
    SELECT id FROM plan WHERE interval = 'month' ORDER BY name LIMIT 1
), new_customers AS (
    INSERT INTO customer (id, email)
    SELECT gen_random_uuid(), 'load-${RUN_TAG}-' || g || '@example.test'
    FROM generate_series(1, ${N}) g
    RETURNING id
)
INSERT INTO subscription (id, customer_id, plan_id, status, renewed_at)
SELECT gen_random_uuid(), c.id, (SELECT id FROM monthly_plan), 'active',
       now() - INTERVAL '1 month'
FROM new_customers c;
ANALYZE customer;
ANALYZE subscription;
SQL
)" || die "seeding failed: ${SEED_OUT}"
SEED_SECS=$(( SECONDS - SEED_START ))
echo "$SEED_OUT" | grep -q "INSERT 0 ${N}$" || die "unexpected psql output: ${SEED_OUT}"
note "seeded in ${SEED_SECS}s"

# --- baselines (same-run deltas only) ----------------------------------------

INS_BEFORE="$(prom_val '^outbox_inserted_total ')"
PUB_BEFORE="$(prom_val '^outbox_published_total ')"
DB_PUB_BEFORE="$(q 'SELECT count(*) FROM renewal_outbox WHERE published_at IS NOT NULL')"
[[ "$INS_BEFORE" =~ ^[0-9]+$ && "$PUB_BEFORE" =~ ^[0-9]+$ && "$DB_PUB_BEFORE" =~ ^[0-9]+$ ]] \
  || die "invalid baselines: inserted=${INS_BEFORE} published=${PUB_BEFORE} db=${DB_PUB_BEFORE}"

# --- trigger + poll to terminal status, sampling heap ------------------------

note "triggering renewal job (async endpoint)…"
TRIGGER_BODY="$(curl -fsS --max-time 30 \
  -X POST "http://localhost:${PRODUCER_PORT}/actuator/renewal-job?force=true" \
  -H 'Content-Type: application/vnd.spring-boot.actuator.v3+json' \
  -H 'Accept: application/json' -d '{}' 2>/dev/null)" || die "trigger POST failed"
EXEC_ID="$(echo "$TRIGGER_BODY" | grep -o '"executionId":[0-9]*' | head -1 | cut -d: -f2)"
[[ "$EXEC_ID" =~ ^[0-9]+$ ]] || die "no executionId in trigger response: ${TRIGGER_BODY}"
note "executionId=${EXEC_ID}; polling every 5s…"

PEAK_HEAP=0
STATUS=""
BODY=""
POLL_START=$SECONDS
while :; do
  BODY="$(curl -fsS "http://localhost:${PRODUCER_PORT}/actuator/renewal-job/${EXEC_ID}" 2>/dev/null)" || BODY=""
  STATUS="$(echo "$BODY" | grep -o '"status":"[A-Z]*"' | head -1 | cut -d'"' -f4)"
  HEAP="$(prom_val '^jvm_memory_used_bytes\{area="heap"')"
  if [[ "$HEAP" =~ ^[0-9]+$ ]] && (( HEAP > PEAK_HEAP )); then PEAK_HEAP=$HEAP; fi
  case "$STATUS" in COMPLETED|FAILED|STOPPED|ABANDONED) break ;; esac
  sleep 5
done
HOST_WALL=$(( SECONDS - POLL_START ))

# --- measurements ------------------------------------------------------------

START_TS="$(echo "$BODY" | grep -o '"startTime":"[^"]*"' | head -1 | cut -d'"' -f4)"
END_TS="$(echo "$BODY" | grep -o '"endTime":"[^"]*"' | head -1 | cut -d'"' -f4)"
WALL=""
if [[ -n "$START_TS" && -n "$END_TS" ]]; then
  WALL=$(( $(date -d "${END_TS%%.*}" +%s) - $(date -d "${START_TS%%.*}" +%s) ))
fi

INS_AFTER="$(prom_val '^outbox_inserted_total ')"
PUB_AFTER="$(prom_val '^outbox_published_total ')"
DB_PUB_AFTER="$(q 'SELECT count(*) FROM renewal_outbox WHERE published_at IS NOT NULL')"
INS_DELTA=n/a; PUB_DELTA=n/a; DB_PUB_DELTA=n/a
[[ "$INS_AFTER" =~ ^[0-9]+$ ]] && INS_DELTA=$(( INS_AFTER - INS_BEFORE ))
[[ "$PUB_AFTER" =~ ^[0-9]+$ ]] && PUB_DELTA=$(( PUB_AFTER - PUB_BEFORE ))
[[ "$DB_PUB_AFTER" =~ ^[0-9]+$ ]] && DB_PUB_DELTA=$(( DB_PUB_AFTER - DB_PUB_BEFORE ))

RATE=n/a
if [[ "$PUB_DELTA" =~ ^[0-9]+$ && -n "$WALL" ]] && (( WALL > 0 )); then
  RATE="$(awk -v p="$PUB_DELTA" -v w="$WALL" 'BEGIN { printf "%.0f", p / w }')"
fi
PEAK_MIB="$(awk -v b="$PEAK_HEAP" 'BEGIN { printf "%.0f", b / 1048576 }')"

echo
echo "==================== load-test report ===================="
echo "seeded                 ${N} extra due-today subscriptions in ${SEED_SECS}s"
echo "job execution          id=${EXEC_ID}  terminal status=${STATUS}"
echo "job wall duration      ${WALL:-n/a}s (execution timestamps; host-side poll ${HOST_WALL}s)"
echo "outbox inserted delta  ${INS_DELTA}"
echo "outbox published delta ${PUB_DELTA} (DB published delta ${DB_PUB_DELTA})"
echo "publish rate           ${RATE} msg/s"
echo "peak producer heap     ${PEAK_MIB} MiB"
echo "=========================================================="
[[ "$STATUS" == "COMPLETED" ]] || exit 1
exit 0
