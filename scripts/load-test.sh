#!/usr/bin/env bash
# Load test on a throwaway stack: a separate Compose project (airline-load, app on :8081) with
# its own database, seeded by SQL to a chosen size, hit by JMeter for a fixed time, then removed.
# The working stack (:8080, :5433) is never touched. Needs only bash, curl and Docker.
#
#   bash scripts/load-test.sh                      # 100 schedules, ~1M bookings, 60 s
#   SCHEDULES=10 bash scripts/load-test.sh         # ~100k bookings: the small baseline
#   DURATION=120 HOT=50 bash scripts/load-test.sh  # longer, more pressure on one flight
#   bash scripts/load-test.sh throttle             # separate: 2 clients guessing references for
#                                                  # 20 s (10 x 404, then 429); results in load/results/throttle
#
# Knobs (environment): SCHEDULES (100) FILL (0.3, share of seats pre-booked) DURATION (60 s)
#   SEARCH LOOKUP CANCEL BOOKING HOT (threads per scenario: 20 10 5 10 20)  RACE (5 customers pick
#   the same seat in the same instant, one request each; exactly one may succeed)  GUESS (0: reference
#   guessers, run after the others; the "throttle" mode sets them up alone)  KEEP=1 (leave the stack up)
# Failures are counted per reason: the machine-readable error code of every response is a column
# of the .jtl (JMeter's sample_variables), so the summary shows e.g. "409 SEAT_UNAVAILABLE x6081",
# and it is copied into JMeter's response message, so the dashboard's Errors tables show the same.
# Output: a summary per scenario (responses by status and error code with percentages), saved to
# load/results/summary.txt and drawn as one pie per scenario in load/results/outcomes.html; the raw
# run in load/results/run.jtl; JMeter's HTML report in load/results/report/index.html (percentiles,
# throughput, statuses over time; its pass/fail pie and Errors table count 5xx only).
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash: keep /load and /results as container paths
cd "$(dirname "$0")/.."

MODE="${1:-load}"
SCHEDULES="${SCHEDULES:-100}"; FILL="${FILL:-0.3}"; DURATION="${DURATION:-60}"
SEARCH="${SEARCH:-20}"; LOOKUP="${LOOKUP:-10}"; CANCEL="${CANCEL:-5}"; BOOKING="${BOOKING:-10}"; HOT="${HOT:-20}"; RACE="${RACE:-5}"; GUESS="${GUESS:-0}"
KEEP="${KEEP:-0}"
BASE_URL="http://localhost:8081"
COMPOSE=(docker compose -p airline-load -f docker-compose.yml -f load/docker-compose.load.yml)
RESULTS=load/results
SEAT_HOLD="${AIRLINE_SEAT_HOLD_ENABLED:-false}"   # passed through to the app by docker-compose.yml
if [[ "$SEAT_HOLD" == true ]]; then
  RESULTS=load/results/seat-hold   # keeps the hold-on figures beside the default run's
fi
if [[ "$MODE" == throttle ]]; then
  # Only the guessers: a tiny seed, no steady traffic, and their own results folder, so the
  # load run's figures are never mixed with tens of thousands of refused requests.
  SCHEDULES=1; DURATION=1; SEARCH=0; LOOKUP=0; CANCEL=0; BOOKING=0; HOT=0; RACE=0
  [[ "$GUESS" == 0 ]] && GUESS=2
  RESULTS=load/results/throttle
elif [[ "$MODE" != load ]]; then
  echo "usage: $0 [load|throttle]"; exit 2
fi

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
RACE_FLIGHT="$(sql 'SELECT id FROM flight_instance WHERE flight_date = current_date + 31 ORDER BY id LIMIT 1')"
RACE_SEAT=30F   # the seed books rows from the front, so the last row is free unless FILL is near 1
# The data set, for the summary and outcomes.html: what the numbers were measured against.
DATASET="$(sql "SELECT (SELECT count(*) FROM flight_schedule) || '|' || (SELECT count(DISTINCT (origin_code, destination_code)) FROM flight_schedule) || '|' || (SELECT count(*) FROM flight_instance) || '|' || (SELECT min(flight_date) || '_to_' || max(flight_date) FROM flight_instance) || '|' || (SELECT max(total_seats) FROM flight_instance) || '|' || (SELECT round(avg(total_seats - available_seats)) FROM flight_instance) || '|' || (SELECT count(*) FROM booking) || '|' || (SELECT count(*) FROM booking WHERE status = 'CANCELLED') || '|' || (SELECT count(*) FROM booking_seat) || '|' || (SELECT available_seats FROM flight_instance WHERE id = $HOT_FLIGHT) || '|' || (SELECT flight_number || '_' || origin_code || '-' || destination_code || '_' || flight_date FROM flight_instance WHERE id = $HOT_FLIGHT)")"
IFS='|' read -r DS_SCHEDULES DS_ROUTES DS_INSTANCES DS_DATES DS_SEATS DS_TAKEN DS_BOOKINGS DS_CANCELLED DS_SEATROWS DS_HOT_FREE DS_HOT_NAME <<< "$DATASET"
DS_DATES="${DS_DATES/_to_/ to }"; DS_HOT_NAME="${DS_HOT_NAME//_/ }"   # sql() strips whitespace
echo "routes: $(grep -c . "$RESULTS/routes.csv"), references: $(grep -c . "$RESULTS/refs.csv"), flight instances: 1..$INSTANCES, hot flight: $HOT_FLIGHT, race: seat $RACE_SEAT on flight $RACE_FLIGHT"

step "3/5 JMeter for ${DURATION}s: threads search=$SEARCH lookup=$LOOKUP cancel=$CANCEL booking=$BOOKING booking-hot=$HOT; $RACE simultaneous requests for one seat; then $GUESS reference guessers for 20 s"
"${COMPOSE[@]}" --profile tools run --rm jmeter -n -t /load/airline-load.jmx \
  -Jhost=app -Jport=8080 -Jduration="$DURATION" \
  -Jsearch_threads="$SEARCH" -Jlookup_threads="$LOOKUP" -Jcancel_threads="$CANCEL" -Jbooking_threads="$BOOKING" -Jhot_threads="$HOT" \
  -Jinstances="$INSTANCES" -Jhot_flight="$HOT_FLIGHT" \
  -Jrace_threads="$RACE" -Jrace_flight="$RACE_FLIGHT" -Jrace_seat="$RACE_SEAT" -Jguess_threads="$GUESS" \
  -Jsample_variables=code,bstatus \
  -l "/${RESULTS#load/}/run.jtl" -j "/${RESULTS#load/}/jmeter.log" -e -o "/${RESULTS#load/}/report" | grep -E "^summary =|Err:" | tail -3

step "4/5 results (elapsed ms per request, from $RESULTS/run.jtl; saved to $RESULTS/summary.txt, pies in $RESULTS/outcomes.html)"
exec > >(tee "$RESULTS/summary.txt") 2>&1
echo "Mode: seat hold $([[ "$SEAT_HOLD" == true ]] && echo "ON (bookings are created HELD)" || echo "off (bookings are confirmed at once)")"
echo "Data set: $DS_SCHEDULES schedules on $DS_ROUTES routes, one flight per schedule per day from $DS_DATES = $DS_INSTANCES flights;"
echo "          $DS_SEATS seats per flight (A320), $DS_TAKEN already booked on each before the run ($DS_BOOKINGS bookings, $DS_CANCELLED of them cancelled, $DS_SEATROWS seat rows);"
echo "          hot flight: $DS_HOT_NAME (id $HOT_FLIGHT) with $DS_HOT_FREE free seats at the start; race: seat $RACE_SEAT on flight $RACE_FLIGHT; $(grep -c . "$RESULTS/refs.csv") live references for lookup and cancel."
echo
# One pie per scenario, by outcome (status + error code): plain HTML and CSS conic-gradient, no libraries.
colour() { case "$1" in *HELD*) echo "#4cc9f0";; *CANCELLED*) echo "#8ecae6";; 2*) echo "#2a9d8f";; *SEAT_UNAVAILABLE*) echo "#e9c46a";; 404*) echo "#8d99ae";; 429*) echo "#7b2cbf";; 5*) echo "#d62828";; *) echo "#f4a261";; esac; }
PIES="$RESULTS/outcomes.html"
cat > "$PIES" <<'HTML'
<!doctype html><meta charset="utf-8"><title>Load test outcomes</title>
<style>body{font:14px system-ui,sans-serif;margin:24px;color:#222}h1{font-size:20px}.grid{display:flex;flex-wrap:wrap;gap:24px}
.card{border:1px solid #ddd;border-radius:8px;padding:16px;width:300px}.card h2{font-size:16px;margin:0 0 12px}.pie{width:140px;height:140px;border-radius:50%;margin:0 auto 12px}
ul{list-style:none;padding:0;margin:0}li{display:flex;align-items:center;gap:8px;margin:4px 0}li span{width:12px;height:12px;border-radius:2px;display:inline-block}small{color:#666}</style>
<h1>Outcomes per scenario</h1><p><small>Every request's HTTP status and, for errors, the machine-readable code from the response body. 409 means the seat was already taken; 404 an unknown reference; 429 a throttled guesser; 5xx would be a system failure.</small></p>
HTML
cat >> "$PIES" <<HTML
<h2 style="font-size:16px">Data set</h2>
<table style="border-collapse:collapse;margin-bottom:20px">
<tr><td style="padding:2px 12px 2px 0">Mode</td><td><b>seat hold $([[ "$SEAT_HOLD" == true ]] && echo "ON" || echo "off")</b>: bookings are $([[ "$SEAT_HOLD" == true ]] && echo "created HELD and confirmed later" || echo "confirmed at once")</td></tr>
<tr><td style="padding:2px 12px 2px 0">Schedules</td><td><b>$DS_SCHEDULES</b> on <b>$DS_ROUTES</b> routes between the 10 seeded airports, one flight per schedule per day</td></tr>
<tr><td style="padding:2px 12px 2px 0">Flights (instances)</td><td><b>$DS_INSTANCES</b>, dates $DS_DATES</td></tr>
<tr><td style="padding:2px 12px 2px 0">Seats</td><td><b>$DS_SEATS</b> per flight (A320, 30 rows A–F); <b>$DS_TAKEN</b> already booked on each flight before the run</td></tr>
<tr><td style="padding:2px 12px 2px 0">Bookings before the run</td><td><b>$DS_BOOKINGS</b> ($DS_CANCELLED cancelled, seats released), <b>$DS_SEATROWS</b> seat rows</td></tr>
<tr><td style="padding:2px 12px 2px 0">Hot flight</td><td>$DS_HOT_NAME (id $HOT_FLIGHT), <b>$DS_HOT_FREE</b> free seats at the start</td></tr>
<tr><td style="padding:2px 12px 2px 0">Race</td><td>seat $RACE_SEAT on flight id $RACE_FLIGHT, free at the start</td></tr>
<tr><td style="padding:2px 12px 2px 0">Lookups and cancels</td><td>$(grep -c . "$RESULTS/refs.csv") live references drawn at random from the bookings above</td></tr>
<tr><td style="padding:2px 12px 2px 0">Load</td><td>${DURATION}s; threads: search $SEARCH, lookup $LOOKUP, cancel $CANCEL, booking $BOOKING, booking-hot $HOT; race $RACE requests once; guess $GUESS for 20 s</td></tr>
</table>
<div class="grid">
HTML
describe() {
  case "$1" in
    search)       echo "Customers looking for flights between two cities on a date. Good: every answer 200, fast, whatever the table size.";;
    lookup)       echo "Customers opening an existing booking with their reference. Good: every answer 200, fast.";;
    cancel)       echo "Customers cancelling bookings, freeing seats while others are booking. Good: every answer 200; a repeated cancel is harmless; seat counts stay right.";;
    booking)      echo "Ordinary sales: one seat each, spread over all flights. Good: 201 when the seat was free, 409 SEAT_UNAVAILABLE when someone already has it (about the pre-booked share), never a seat sold twice.";;
    booking-hot)  echo "A sale on one popular flight: $HOT customers booking seats on the same flight at once, each waiting its turn for that flight. Good: 201 for every free seat exactly once ($DS_HOT_FREE here, plus any released by a cancel), then 409 for everyone else, still fast.";;
    booking-race) echo "$RACE customers clicking the very same seat in the same instant. Good: exactly one 201, the rest 409: the double-booking guarantee seen over HTTP.";;
    guess)        echo "Someone trying random references to find other people's bookings. Good: ten 404 BOOKING_NOT_FOUND, then only 429 RATE_LIMITED, refused without touching the database.";;
  esac
}
# .jtl columns: timeStamp,elapsed,label,responseCode,...,code,bstatus (the first four never contain
# commas; the last two, via sample_variables, are the application's error code and the booking
# status from a success body, "none" when absent). An outcome reads e.g. "201 HELD" or
# "409 SEAT_UNAVAILABLE".
outcome_counts() {  # label -> "outcome<TAB>count" lines
  tail -n +2 "$RESULTS/run.jtl" | awk -F, -v l="$1" \
    '$3 == l { c[$4 ($(NF-1) == "none" ? "" : " " $(NF-1)) ($NF ~ /^[A-Z]+$/ ? " " $NF : "")]++ } END { for (k in c) print k "\t" c[k] }'
}
SORTED="$(mktemp)"; trap 'rm -f "$SORTED"' EXIT
printf '%-12s %8s %9s %6s %6s %6s %6s %6s   %s\n' scenario requests req/s p50ms p90ms p95ms p99ms maxms "responses by status and error code"
for label in search lookup cancel booking booking-hot booking-race guess; do
  tail -n +2 "$RESULTS/run.jtl" | awk -F, -v l="$label" '$3 == l { print $2 }' | sort -n > "$SORTED"
  n="$(grep -c . "$SORTED" || true)"
  [[ "$n" -eq 0 ]] && continue
  pct() { sed -n "$(( (n * $1 + 99) / 100 ))p" "$SORTED"; }
  codes="$(outcome_counts "$label" | awk -F'\t' -v n="$n" '{ c[$1] = $2 } END { for (k in c) if (length(c) == 1) printf "%s x%d", k, c[k]; else printf "%s x%d (%.1f%%)  ", k, c[k], 100 * c[k] / n }')"
  seconds="$DURATION"; [[ "$label" == guess ]] && seconds=20   # the guessers always run for 20 s
  printf '%-12s %8d %9.1f %6s %6s %6s %6s %6s   %s\n' "$label" "$n" "$(awk -v n="$n" -v d="$seconds" 'BEGIN { printf "%.1f", n / d }')" \
    "$(pct 50)" "$(pct 90)" "$(pct 95)" "$(pct 99)" "$(tail -1 "$SORTED")" "$codes"
  # the pie: "outcome count" lines -> conic-gradient stops and a legend
  stops=""; legend=""; from=0
  while IFS=$'\t' read -r outcome count; do
    to=$(awk -v f="$from" -v c="$count" -v n="$n" 'BEGIN { printf "%.2f", f + 100 * c / n }')
    stops+="${stops:+,}$(colour "$outcome") ${from}% ${to}%"
    legend+="<li><span style=\"background:$(colour "$outcome")\"></span>$outcome &mdash; $count ($(awk -v c="$count" -v n="$n" 'BEGIN { printf "%.1f", 100 * c / n }')%)</li>"
    from="$to"
  done < <(outcome_counts "$label" | sort -t "$(printf '\t')" -k2,2nr)
  printf '<div class="card"><h2>%s <small>%d requests</small></h2><p><small>%s</small></p><div class="pie" style="background:conic-gradient(%s)"></div><ul>%s</ul></div>\n' "$label" "$n" "$(describe "$label")" "$stops" "$legend" >> "$PIES"
done
echo '</div>' >> "$PIES"
echo
echo "pool: connection waits that timed out = $(curl -s "$BASE_URL/actuator/metrics/hikaricp.connections.timeout" | sed -E 's/.*"value":([0-9.]+).*/\1/'), " \
     "slowest connection acquire = $(curl -s "$BASE_URL/actuator/metrics/hikaricp.connections.acquire" | sed -E 's/.*"MAX","value":([0-9.]+).*/\1/') s"
if [[ "$HOT" -gt 0 ]]; then
  echo "hot flight $HOT_FLIGHT: $(sql "SELECT available_seats FROM flight_instance WHERE id = $HOT_FLIGHT") seats left"
fi
if [[ "$RACE" -gt 0 ]]; then
  # ACTIVE, or HELD when the stack runs with AIRLINE_SEAT_HOLD_ENABLED=true
  echo "race: $(sql "SELECT count(*) FROM booking_seat WHERE flight_instance_id = $RACE_FLIGHT AND seat_number = '$RACE_SEAT' AND status IN ('ACTIVE', 'HELD')") live booking for seat $RACE_SEAT after $RACE simultaneous requests (expected exactly 1)"
fi
if [[ "$GUESS" -gt 0 ]]; then
  echo "guess: $(tail -n +2 "$RESULTS/run.jtl" | awk -F, '$3 == "guess" && $4 == 404' | wc -l | tr -d ' ') misses answered 404, then $(tail -n +2 "$RESULTS/run.jtl" | awk -F, '$3 == "guess" && $4 == 429' | wc -l | tr -d ' ') x 429 RATE_LIMITED (expected 10, then the rest)"
fi
echo "inventory invariant: $(sql "SELECT count(*) FROM flight_instance i WHERE available_seats <> total_seats - (SELECT count(*) FROM booking_seat bs WHERE bs.flight_instance_id = i.id AND bs.status IN ('ACTIVE','HELD'))") flights where available_seats != total - taken (expected 0)"
echo "report: $RESULTS/report/index.html, outcome pies: $RESULTS/outcomes.html"

if [[ "$KEEP" == 1 ]]; then
  step "5/5 KEEP=1: stack left running on $BASE_URL; remove it with: ${COMPOSE[*]} down -v"
else
  step "5/5 removing the throwaway stack"
  "${COMPOSE[@]}" down -v >/dev/null
fi
