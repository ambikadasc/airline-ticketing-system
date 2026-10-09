#!/usr/bin/env bash
# End-to-end smoke test of the running system: create a schedule, search, view the seat map,
# book, hit a seat conflict, cancel (twice) and rebook.
#
# Needs only bash and curl. Run it against a fresh database:
#   docker compose down -v && docker compose up --build -d
#   bash scripts/smoke-test.sh              # or BASE_URL=http://host:8080 bash scripts/smoke-test.sh
#
# Exits non-zero at the first failed check.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
API="$BASE_URL/api/v1"
BODY_FILE="$(mktemp)"
trap 'rm -f "$BODY_FILE"' EXIT

# --- helpers -------------------------------------------------------------------------------

# request METHOD PATH [JSON]: sets $CODE (HTTP status) and $BODY (response body).
request() {
  local method="$1" path="$2" data="${3:-}"
  if [[ -n "$data" ]]; then
    CODE=$(curl -s -o "$BODY_FILE" -w '%{http_code}' -X "$method" "$API$path" \
      -H 'Content-Type: application/json' -d "$data")
  else
    CODE=$(curl -s -o "$BODY_FILE" -w '%{http_code}' -X "$method" "$API$path")
  fi
  BODY="$(cat "$BODY_FILE")"
}

# field NAME: the first value of "NAME" in $BODY (strings without quotes).
# Extended regex (-E) so the alternation works with GNU, BSD (macOS) and busybox sed alike.
field() {
  printf '%s' "$BODY" | sed -E -n 's/.*"'"$1"'":("[^"]*"|[^,}]*).*/\1/p' | head -1 | tr -d '"'
}

# seat_status SEAT: AVAILABLE or BOOKED for one seat in a seat-map $BODY.
seat_status() {
  printf '%s' "$BODY" | grep -o "{\"seatNumber\":\"$1\",\"status\":\"[A-Z]*\"}" | sed 's/.*"status":"\([A-Z]*\)".*/\1/'
}

count() {
  printf '%s' "$BODY" | grep -o "$1" | wc -l | tr -d ' '
}

check() {
  local description="$1"; shift
  if "$@"; then
    echo "PASS  $description"
  else
    echo "FAIL  $description"
    echo "      HTTP $CODE: $BODY"
    exit 1
  fi
}

book() {
  local passengers="" seat
  for seat in "$@"; do
    passengers+="${passengers:+,}{\"name\":\"Passenger $seat\",\"seatNumber\":\"$seat\"}"
  done
  request POST /bookings "{\"flightInstanceId\":$FLIGHT_ID,\"passengers\":[$passengers]}"
}

# The next Monday at least one day ahead. Date arithmetic is done on epoch seconds so it works with
# GNU date (Linux, Git Bash), BSD date (macOS, -r) and busybox date (-d @epoch) alike.
DAYS_AHEAD=$(( 8 - $(date -u +%u) ))
TARGET_EPOCH=$(( $(date -u +%s) + DAYS_AHEAD * 86400 ))
DATE=$(date -u -d "@$TARGET_EPOCH" +%F 2>/dev/null || date -u -r "$TARGET_EPOCH" +%F)
echo "Smoke test against $BASE_URL, flight date $DATE"

# --- the scenario ----------------------------------------------------------------------------

# 1. Create the schedule
request POST /admin/schedules '{"flightNumber":"XY101","origin":"DXB","destination":"LHR","departureTime":"09:30","arrivalTime":"13:45","aircraftId":1,"daysOfOperation":["MONDAY","WEDNESDAY","FRIDAY"]}'
GENERATED="$(field generatedInstances)"
check "1. create schedule XY101 -> 201 with more than 150 instances" \
  test "$CODE" = 201 -a "${GENERATED:-0}" -gt 150

# 2. Search
request GET "/flights?origin=DXB&destination=LHR&date=$DATE"
FLIGHT_ID="$(field flightInstanceId)"
check "2. search -> one flight with 180 available seats" \
  test "$CODE" = 200 -a "$(count '"flightInstanceId"')" = 1 -a "$(field availableSeats)" = 180

# 3. Seat map
request GET "/flights/$FLIGHT_ID/seats"
check "3. seat map -> 180 seats, all AVAILABLE" \
  test "$CODE" = 200 -a "$(count '"seatNumber"')" = 180 -a "$(count '"status":"AVAILABLE"')" = 180

# 4. Book 12A + 12B
book 12A 12B
REFERENCE="$(field bookingReference)"
check "4. book 12A+12B -> 201 CONFIRMED ($REFERENCE)" \
  test "$CODE" = 201 -a "$(field status)" = CONFIRMED

# 5. Conflict on 12B
book 12B 12C
check "5. book 12B+12C -> 409 SEAT_UNAVAILABLE listing 12B" \
  test "$CODE" = 409 -a "$(field code)" = SEAT_UNAVAILABLE -a "$(count '"unavailableSeats":\["12B"\]')" = 1

# 6. Seat map reflects the booking only
request GET "/flights/$FLIGHT_ID/seats"
check "6. seat map -> 12A, 12B BOOKED; 12C AVAILABLE; 178 available" \
  test "$(seat_status 12A)" = BOOKED -a "$(seat_status 12B)" = BOOKED -a "$(seat_status 12C)" = AVAILABLE \
       -a "$(field availableSeats)" = 178

# 7. Cancel, twice
request POST "/bookings/$REFERENCE/cancel"
check "7a. cancel -> 200 CANCELLED" test "$CODE" = 200 -a "$(field status)" = CANCELLED
request POST "/bookings/$REFERENCE/cancel"
check "7b. cancel again -> 200 CANCELLED" test "$CODE" = 200 -a "$(field status)" = CANCELLED

# 8. Rebook a released seat
book 12B 12C
check "8. book 12B+12C -> 201" test "$CODE" = 201

# 9. Availability
request GET "/flights?origin=DXB&destination=LHR&date=$DATE"
check "9. search -> 178 available seats" test "$(field availableSeats)" = 178

echo "All smoke checks passed."
