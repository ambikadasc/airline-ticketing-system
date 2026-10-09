#!/usr/bin/env bash
# Load test on a throwaway stack: a separate Compose project (airline-load, app on :8081) with
# its own database, seeded by SQL to a chosen size, hit by JMeter for a fixed time, then removed.
# The working stack (:8080, :5433) is never touched. Needs only bash, curl and Docker.
#
#   bash scripts/load-test.sh                      # 100 schedules, ~1M bookings, 60 s
#   SCHEDULES=10 bash scripts/load-test.sh         # ~100k bookings: the small baseline
#   DURATION=120 HOT=50 bash scripts/load-test.sh  # longer, more pressure on one flight
#
# Knobs (environment): SCHEDULES (100) FILL (0.3, share of seats pre-booked) DURATION (60 s)
#   SEARCH LOOKUP BOOKING HOT (threads per scenario: 20 10 10 20)  KEEP=1 (leave the stack up)
# Output: a summary per scenario below, the raw run in load/results/run.jtl and JMeter's HTML
# report in load/results/report/index.html.
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash: keep /load and /results as container paths
cd "$(dirname "$0")/.."

SCHEDULES="${SCHEDULES:-100}"; FILL="${FILL:-0.3}"; DURATION="${DURATION:-60}"
SEARCH="${SEARCH:-20}"; LOOKUP="${LOOKUP:-10}"; BOOKING="${BOOKING:-10}"; HOT="${HOT:-20}"
KEEP="${KEEP:-0}"
BASE_URL="http://localhost:8081"
COMPOSE=(docker compose -p airline-load -f docker-compose.yml -f load/docker-compose.load.yml)
RESULTS=load/results

step() { echo; echo "== $*"; }
sql()  { "${COMPOSE[@]}" exec -T db psql -U airline -d airline -Atq -c "$1" | tr -d '[:space:]'; }

step "1/5 fresh throwaway stack (Compose project airline-load, app on $BASE_URL)"
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build db app
for i in $(seq 1 60); do
  curl -fsS "$BASE_URL/actuator/health" 2>/dev/null | grep -q '"UP"' && break
  if [[ $i -eq 60 ]]; then echo "The application never became healthy"; "${COMPOSE[@]}" logs app | tail -20; exit 1; fi
  sleep 2
done

step "2/5 seed: $SCHEDULES schedules x 365 days, $FILL of every flight's seats already booked"
mkdir -p "$RESULTS"; rm -rf "$RESULTS/report" "$RESULTS/run.jtl" "$RESULTS/jmeter.log"
"${COMPOSE[@]}" exec -T db psql -U airline -d airline -v schedules="$SCHEDULES" -v fill="$FILL" -f /load/seed.sql
# JMeter's inputs: every seeded route, and 10,000 random live references (no misses, so the
# lookup throttle never trips).
csv() { "${COMPOSE[@]}" exec -T db psql -U airline -d airline -Atq -c "COPY ($1) TO STDOUT" | tr -d '\r'; }
csv "SELECT DISTINCT origin_code || ',' || destination_code FROM flight_schedule" > "$RESULTS/routes.csv"
csv "SELECT reference FROM booking WHERE status = 'CONFIRMED' ORDER BY random() LIMIT 10000" > "$RESULTS/refs.csv"
INSTANCES="$(sql 'SELECT max(id) FROM flight_instance')"
HOT_FLIGHT="$(sql 'SELECT id FROM flight_instance WHERE flight_date = current_date + 30 ORDER BY id LIMIT 1')"
echo "routes: $(grep -c . "$RESULTS/routes.csv"), references: $(grep -c . "$RESULTS/refs.csv"), flight instances: 1..$INSTANCES, hot flight: $HOT_FLIGHT"

step "3/5 JMeter for ${DURATION}s: threads search=$SEARCH lookup=$LOOKUP booking=$BOOKING booking-hot=$HOT"
"${COMPOSE[@]}" --profile tools run --rm jmeter -n -t /load/airline-load.jmx \
  -Jhost=app -Jport=8080 -Jduration="$DURATION" \
  -Jsearch_threads="$SEARCH" -Jlookup_threads="$LOOKUP" -Jbooking_threads="$BOOKING" -Jhot_threads="$HOT" \
  -Jinstances="$INSTANCES" -Jhot_flight="$HOT_FLIGHT" \
  -l /results/run.jtl -j /results/jmeter.log -e -o /results/report | grep -E "^summary =|Err:" | tail -3

step "4/5 results (elapsed ms per request, from $RESULTS/run.jtl)"
# .jtl columns: timeStamp,elapsed,label,responseCode,... (the first four never contain commas)
SORTED="$(mktemp)"; trap 'rm -f "$SORTED"' EXIT
printf '%-12s %8s %9s %7s %7s %7s %7s   %s\n' scenario requests req/s p50 p95 p99 max "responses by status"
for label in search lookup booking booking-hot; do
  tail -n +2 "$RESULTS/run.jtl" | awk -F, -v l="$label" '$3 == l { print $2 }' | sort -n > "$SORTED"
  n="$(grep -c . "$SORTED" || true)"
  [[ "$n" -eq 0 ]] && continue
  pct() { sed -n "$(( (n * $1 + 99) / 100 ))p" "$SORTED"; }
  codes="$(tail -n +2 "$RESULTS/run.jtl" | awk -F, -v l="$label" '$3 == l { c[$4]++ } END { for (k in c) printf "%s x%d  ", k, c[k] }')"
  printf '%-12s %8d %9.1f %7s %7s %7s %7s   %s\n' "$label" "$n" "$(awk -v n="$n" -v d="$DURATION" 'BEGIN { printf "%.1f", n / d }')" \
    "$(pct 50)" "$(pct 95)" "$(pct 99)" "$(tail -1 "$SORTED")" "$codes"
done
echo
echo "pool: connection waits that timed out = $(curl -s "$BASE_URL/actuator/metrics/hikaricp.connections.timeout" | sed -E 's/.*"value":([0-9.]+).*/\1/'), " \
     "slowest connection acquire = $(curl -s "$BASE_URL/actuator/metrics/hikaricp.connections.acquire" | sed -E 's/.*"MAX","value":([0-9.]+).*/\1/') s"
echo "hot flight $HOT_FLIGHT: $(sql "SELECT available_seats FROM flight_instance WHERE id = $HOT_FLIGHT") seats left"
echo "inventory invariant: $(sql "SELECT count(*) FROM flight_instance i WHERE available_seats <> total_seats - (SELECT count(*) FROM booking_seat bs WHERE bs.flight_instance_id = i.id AND bs.status IN ('ACTIVE','HELD'))") flights where available_seats != total - taken (expected 0)"
echo "report: $RESULTS/report/index.html"

if [[ "$KEEP" == 1 ]]; then
  step "5/5 KEEP=1: stack left running on $BASE_URL; remove it with: ${COMPOSE[*]} down -v"
else
  step "5/5 removing the throwaway stack"
  "${COMPOSE[@]}" down -v >/dev/null
fi
