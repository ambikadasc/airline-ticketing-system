# Airline Reservation System

Backend for a single airline. A back office defines flight schedules; customers search flights,
view seat maps, book seats and cancel bookings, up to 365 days ahead (configurable). Double booking is impossible,
including under concurrent requests.

**Stack:** Java 21 · Spring Boot 4.1 · PostgreSQL 16 · Flyway · JUnit 5 + Testcontainers ·
Docker Compose

| Document | What it covers |
| --- | --- |
| [High-level design](hld/architecture.md) ([PDF](hld/architecture.pdf)) | Architecture, components, request flows, flight generation and concurrency strategies, decisions, assumptions, scaling |
| [Low-level design](lld/design.md) | Packages, classes, API reference, validation, error catalogue, transaction boundaries, lock order |
| [Decision records](docs/adr/) | Six ADRs: context, decision, alternatives, consequences, when to revisit |
| [Schema](db/schema.sql) · [migrations](db/migrations/) · [ER diagram](hld/diagrams/er.png) | Tables, keys, constraints, indexes |
| [Decision log](DECISIONS.md) | Every smaller decision, one line each, by phase |
| Swagger UI | http://localhost:8080/swagger-ui.html (while running) |

---

## Setup Instructions

### Dependencies
- **To run:** Docker with Docker Compose. Nothing else: the image builds the code itself.
- **To build and test locally:** JDK 21 and Docker. The tests start PostgreSQL in a container
  (Testcontainers). Maven is not needed: use the wrapper (`./mvnw`, or `mvnw.cmd` on Windows).

### Build
```bash
./mvnw verify          # compiles and runs all 353 tests against a real PostgreSQL (needs Docker)
```

### Run
```bash
docker compose up --build          # PostgreSQL + the application on http://localhost:8080
```
Then open http://localhost:8080/swagger-ui.html, or check http://localhost:8080/actuator/health.
Stop with `docker compose down -v` (`-v` also deletes the database).

PostgreSQL is published on `localhost:5433` (not 5432, so a PostgreSQL already running on your
machine is not in the way); inside Compose the application reaches it as `db:5432`.

Developer alternative, running the app from the source tree against the Compose database:
```bash
docker compose up -d db
./mvnw spring-boot:run
```

**Optional seat hold** (off by default; see [ADR 0003](docs/adr/0003-seat-hold-behind-a-flag.md)):
```bash
docker compose up -d db
docker compose run --rm --service-ports -e AIRLINE_SEAT_HOLD_ENABLED=true app
```
Bookings are then `HELD` for 10 minutes and must be confirmed with
`POST /api/v1/bookings/{reference}/confirm`.

### Configuration
Every business setting lives in [`application.yml`](src/main/resources/application.yml) under
`airline.*`. Each can be changed without touching code, through an environment variable (Spring
Boot maps `AIRLINE_BOOKING_WINDOW_DAYS` to `airline.booking-window-days`):

| Setting | Environment variable | Default | Effect |
| --- | --- | --- | --- |
| `airline.booking-window-days` | `AIRLINE_BOOKING_WINDOW_DAYS` | `365` | How far ahead flights are generated, searchable and bookable (today + N days, both ends included) |
| `airline.max-seats-per-booking` | `AIRLINE_MAX_SEATS_PER_BOOKING` | `9` | Most passengers in one booking |
| `airline.instance-job-cron` | `AIRLINE_INSTANCE_JOB_CRON` | `0 5 0 * * *` | When the daily job tops up the window (UTC) |
| `airline.retry-after` | `AIRLINE_RETRY_AFTER` | `PT1S` | `Retry-After` sent with a 503 (`LOCK_TIMEOUT`, `RETRY_LATER`) |
| `airline.seat-hold.enabled` | `AIRLINE_SEAT_HOLD_ENABLED` | `false` | Optional seat hold |
| `airline.seat-hold.ttl` | `AIRLINE_SEAT_HOLD_TTL` | `PT10M` | How long a hold lasts |
| `airline.lookup-throttle.max-misses` | `AIRLINE_LOOKUP_THROTTLE_MAX_MISSES` | `10` | Unsuccessful booking lookups a client may make per window before 429 |
| `airline.lookup-throttle.window` | `AIRLINE_LOOKUP_THROTTLE_WINDOW` | `PT1M` | The window for that count |
| `spring.datasource.hikari.connection-init-sql` | `SPRING_DATASOURCE_HIKARI_CONNECTIONINITSQL` | `SET lock_timeout = '3s'` | How long a write waits for a flight lock (while holding a connection) |
| `spring.datasource.hikari.connection-timeout` | `SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT` | `2000` (ms) | How long a request waits for a pooled connection before 503 `RETRY_LATER` |
| `spring.datasource.hikari.maximum-pool-size` | `SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE` | `10` | Database connections per application instance |

**Connection pool.** Ten connections per instance is a deliberate default, not a small one: a
booking holds a connection for a few milliseconds, pools are best kept near `2 × CPU cores` of the
database host, and a bigger pool does not help a busy flight (its writers queue on the row lock).
The ceiling is PostgreSQL's `max_connections` (100 by default) shared by all instances. If
connection waits appear (503 `RETRY_LATER`) while the database is idle, raise
`SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE`; the further steps (more instances, `max_connections`
or PgBouncer, a read pool, rate limiting) are in the HLD, each with its trigger. Hikari's
`hikaricp.connections.pending` and `.timeout` metrics show when the pool is the bottleneck.

With Docker Compose, set any of the `AIRLINE_*` variables in your shell or in a `.env` file next to
`docker-compose.yml`; Compose passes them through, and unset ones keep the default:
```bash
AIRLINE_BOOKING_WINDOW_DAYS=180 docker compose up --build
```
A longer window takes effect at the next startup or daily job, which generates the extra dates. A
shorter one is enforced at once by search and booking; flights already generated beyond it are
simply no longer offered.

Format rules (seat and flight-number formats, the reference format, row limits) stay in code on
purpose: they mirror database column sizes and constraints, so changing them needs a migration,
not a setting.

### Database initialization
Automatic. On startup Flyway applies [`db/migrations`](db/migrations/):
- `V1`–`V3` create the schema, `V5` adds integrity rules (a composite foreign key and format checks)
- `V4` seeds the reference data, which is read-only (there is no CRUD API for it):
  - **10 airports:** DXB, LHR, JFK, SIN, KHI, LHE, ISB, DOH, FRA, CDG
  - **3 aircraft:** id 1 A320 (30 rows × ABCDEF = 180 seats), id 2 B777 (40 × ABCDEFGHJK = 400),
    id 3 ATR72 (18 × ABCD = 72)

Hibernate only validates the schema; it never changes it.

### Smoke test
With the stack running on a **fresh** database:
```bash
docker compose down -v && docker compose up --build -d
bash scripts/smoke-test.sh
```
The script needs only bash and curl. It runs the whole scenario and prints PASS or FAIL per
step: create a schedule, search, seat map, book, a seat conflict, cancel twice, rebook, and
retry safely with an `Idempotency-Key` (same booking back; the key reused for another request → 422).

### Load test
A JMeter run against a **separate, throwaway** stack, seeded by SQL to a chosen size. The
working stack and its database are never touched, and the throwaway stack is removed at the end:
```bash
bash scripts/load-test.sh                 # 100 schedules, 36,500 flights, ~1M bookings, 60 s
SCHEDULES=10 bash scripts/load-test.sh    # the ~100k-booking baseline
bash scripts/load-test.sh throttle        # separate run: clients guessing references (see below)
AIRLINE_SEAT_HOLD_ENABLED=true bash scripts/load-test.sh   # same scenarios with the seat hold on
```
Each mode writes to its own folder (`load/results/`, `load/results/seat-hold/`,
`load/results/throttle/`), and the summary and outcome page state the mode and the data set at
the top, so hold-off and hold-on figures sit side by side; with the hold on, bookings show as
`201 HELD` instead of `201 CONFIRMED`.
Needs bash, curl and Docker only: JMeter runs from the `alpine/jmeter` image, nothing is
installed. The script brings up Compose project `airline-load` (same image, app on
`127.0.0.1:8081`, database not published), runs `load/seed.sql` (schedules on every route, a
year of flights, `FILL` = 30 % of every flight's seats already booked, every tenth booking
cancelled), runs `load/airline-load.jmx` for `DURATION` seconds with five steady scenarios (search,
lookup by reference, cancellation of seeded bookings, bookings spread over all flights, bookings
on one hot flight; thread counts `SEARCH` `LOOKUP` `CANCEL` `BOOKING` `HOT`, default
20/10/5/10/20) and one burst (`RACE`, default 5: customers who pick the **same seat on the same
flight** in the same instant, one request each, of which exactly one may succeed). It prints
requests/s and p50/p90/p95/p99 per scenario with the responses **by status and error code**
(the `code` of every error response is extracted by JMeter and written as a column of the
`.jtl`, so refusals are counted per reason), checks that the raced seat has exactly one active
booking and the inventory invariant on every flight, and ends with
`down -v` (`KEEP=1` leaves the stack up). The summary, with percentages per status and error
code, is also saved to `load/results/summary.txt`. JMeter's HTML dashboard is
`load/results/report/index.html`: **Dashboard** has the pass/fail pie and the Statistics table
(throughput, p90/p95/p99, Error % per scenario); **Charts** has Over Time (response times,
active threads), Throughput (hits per second, **Codes Per Second**: HTTP statuses across the
run) and Response Times (percentiles, distribution). There a request fails only on a 5xx: a 409
(seat already taken) or a 404 is a correct answer, not an error, so the pie stays green unless
the system itself failed, and the Errors table names any 5xx by code (`503/RETRY_LATER`). The
outcome mix per scenario (`201`, `409 SEAT_UNAVAILABLE`, …, with percentages) is its own page,
`load/results/outcomes.html`, generated by the script: a **Data set** table (schedules, routes,
flights and dates, seats per flight and how many were pre-booked, bookings and seat rows, the hot
and race flights with their free seats, the thread counts), then one pie per scenario with a
plain-language description of what it simulates and what a good result looks like. The same
data-set lines head `summary.txt`. All of it is git-ignored and overwritten by the next run.

**Throttle test, separately.** `bash scripts/load-test.sh throttle` runs only the guessing
scenario: 2 clients requesting random references for 20 s against a tiny seed, with results in
`load/results/throttle/` (its own summary, pies and dashboard). It is a separate run for two
reasons: every JMeter thread leaves the container with the same IP address and the lookup
throttle is per client address, so guessers running beside the steady scenarios would throttle
the legitimate lookups and cancels too; and a guesser being refused tens of thousands of times
in 20 s is a success for the system but would drown every other percentage in the load run's
figures. Expected: 10 × 404 `BOOKING_NOT_FOUND`, then only 429 `RATE_LIMITED`.
The figures are under [Measured](#measured).

What the scenarios simulate, in plain terms:

| Scenario | What it simulates | What a good result looks like |
| --- | --- | --- |
| `search` | Customers looking for flights between two cities on a date | Every answer 200, fast, regardless of how many bookings exist |
| `lookup` | Customers opening an existing booking with their reference | Every answer 200, fast |
| `cancel` | Customers cancelling bookings, which frees their seats while others are booking | Every answer 200; a repeated cancel is harmless; the seat counts stay right |
| `booking` | Ordinary sales: customers booking one seat each, spread over all flights | 201 when the seat was free; 409 `SEAT_UNAVAILABLE` when someone already has it; never a seat sold twice |
| `booking-hot` | A sale on one popular flight: 20 customers booking seats on the same flight at once, so every request waits its turn for that flight | 201 for every free seat exactly once, then 409 for everyone else; still fast because each turn is a few milliseconds |
| `booking-race` | Five customers clicking the very same seat in the same instant | Exactly one 201 and four 409s: the double-booking guarantee, seen over HTTP |
| `guess` (throttle run only) | Someone trying random references to find other people's bookings | Ten 404 `BOOKING_NOT_FOUND`, then only 429 `RATE_LIMITED` for the rest of the minute, refused without touching the database |

JMeter is the widely used standard, has a ready HTML report and needs no code; the code-first
alternatives are k6 (JavaScript) and Gatling (Java/Scala), whose scripts read better in version
control than a `.jmx` file.

Adding a scenario: every thread group in `airline-load.jmx` has the same shape, so copy one
(`lookup` for a GET, `booking` for a POST with a body), change its `testname`, the
`${__P(..._threads,0)}` property and the sampler's path or body, then add the knob to
`scripts/load-test.sh` (defaults line, `-J` argument, label in the summary loop). The same file
opens in the JMeter GUI (download Apache JMeter, run `bin/jmeter`, File → Open): right-click the
test plan → Add → Threads (Users) → Thread Group, then Add → Sampler → HTTP Request, type the
same `${__P(...)}` expressions into the fields, save. The GUI is for building and debugging a
plan (a "View Results Tree" listener shows each request and response); load is always run
headless (`-n`), as the script does.

---

## API

Base path `/api/v1`, JSON, all times UTC. Full reference with examples:
[LLD §4](lld/design.md#4-api-reference); interactive: Swagger UI.

| Method | Path | Purpose | Success |
| --- | --- | --- | --- |
| POST | `/admin/schedules` | Create a schedule and generate its flights for 365 days | 201 + `Location` |
| GET | `/admin/schedules/{id}` | Fetch a schedule | 200 |
| GET | `/flights?origin=&destination=&date=` | Search flights | 200 (`[]` if none) |
| GET | `/flights/{flightInstanceId}/seats` | Seat map: every seat AVAILABLE or BOOKED | 200 |
| POST | `/bookings` | Book one or more seats (all or nothing); optional `Idempotency-Key` header makes a retry return the same booking | 201 + `Location` |
| GET | `/bookings/{reference}` | Fetch a booking | 200 |
| POST | `/bookings/{reference}/cancel` | Cancel; repeating it returns the same result | 200 |
| POST | `/bookings/{reference}/confirm` | Confirm a held booking (seat hold only) | 200 |
| POST | `/admin/instance-window/extend` | Generate any missing flights up to the end of the booking window (idempotent; same as the daily job) | 200 `{"inserted": n, "windowEnd": date}` |

### Walkthrough with curl
```bash
# 1. Back office: XY101 Dubai -> London, Mon/Wed/Fri 09:30-13:45 UTC, on the A320
curl -s -X POST localhost:8080/api/v1/admin/schedules -H 'Content-Type: application/json' -d '{
  "flightNumber": "XY101", "origin": "DXB", "destination": "LHR",
  "departureTime": "09:30", "arrivalTime": "13:45", "aircraftId": 1,
  "daysOfOperation": ["MONDAY", "WEDNESDAY", "FRIDAY"]}'
# -> 201 {"id":1, ..., "generatedInstances":158}   (Mon/Wed/Fri for a year, plus one look-ahead day)

# 2. Search a Monday (any date from today up to 365 days ahead)
curl -s 'localhost:8080/api/v1/flights?origin=DXB&destination=LHR&date=2026-11-02'
# -> [{"flightInstanceId":42,"flightNumber":"XY101",...,"availableSeats":180}]

# 3. Seat map
curl -s localhost:8080/api/v1/flights/42/seats

# 4. Book two seats. The Idempotency-Key is optional: repeat the exact command after a timeout
#    and you get the same 201 and reference back instead of a seat conflict.
curl -s -X POST localhost:8080/api/v1/bookings -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 6f1c2a9e-order-1001' -d '{
  "flightInstanceId": 42, "passengers": [
    {"name": "Ayesha Khan", "seatNumber": "12A"}, {"name": "Bilal Khan", "seatNumber": "12B"}]}'
# -> 201 {"bookingReference":"K7M2QX",...,"status":"CONFIRMED"}

# 5. Someone else wants 12B -> 409 SEAT_UNAVAILABLE, unavailableSeats ["12B"]; 12C is not booked either

# 6. Cancel (repeatable); the seats are bookable again at once
curl -s -X POST localhost:8080/api/v1/bookings/K7M2QX/cancel
```
Replace `42` and `K7M2QX` with the values from your responses.

### Demo walkthrough (Swagger UI)
The same story clicked through http://localhost:8080/swagger-ui.html ("Try it out" on each
operation) on a fresh stack (`docker compose down -v && docker compose up --build -d`). Each
step names the design point it shows.

| # | Operation | Input | What to see | What it shows |
| --- | --- | --- | --- | --- |
| 1 | `POST /api/v1/admin/schedules` | the XY101 body from the curl walkthrough | 201, `generatedInstances` ≈ 158 | Flights are materialised on creation: one row per operating date to the end of the booking window |
| 2 | `GET /api/v1/flights` | `origin` DXB, `destination` LHR, `date` = a Monday within the next year | 200, one flight, `availableSeats` 180 | Search reads one indexed table and a maintained counter, no counting |
| 3 | `GET /api/v1/flights/{flightInstanceId}/seats` | the id from step 2 | 180 seats, all `AVAILABLE` | Seat map = aircraft layout minus taken seats; two states only |
| 4 | `POST /api/v1/bookings` | `{"flightInstanceId": <id>, "passengers": [{"name": "Ayesha Khan", "seatNumber": "12A"}, {"name": "Bilal Khan", "seatNumber": "12B"}]}` | 201, `bookingReference`, `status` `CONFIRMED` | Seats taken under the flight's row lock; a random 6-character reference |
| 5 | step 4 again, same body | | 409 `SEAT_UNAVAILABLE`, `unavailableSeats` `["12A","12B"]` | All or nothing, and the error names the seats |
| 5b | step 4 again with header `Idempotency-Key: demo-1` twice, then once more with seat `12C` | | 201 with a new reference, then 201 with the **same** reference, then 422 `IDEMPOTENCY_KEY_REUSED` | A retry is safe: the key returns the booking it created; the same key cannot be reused for a different request (ADR 0007) |
| 6 | `GET /api/v1/bookings/{reference}` | the reference from step 4 | 200 with both passengers | Lookup by reference only (ADR 0005) |
| 7 | `POST /api/v1/bookings/{reference}/cancel`, twice | | 200 `CANCELLED` both times; step 3 shows 12A/12B `AVAILABLE` again | Idempotent cancellation; seats released at once |
| 8 | `GET /api/v1/bookings/ZZZZZZ`, eleven times | | ten 404 `BOOKING_NOT_FOUND`, then 429 `RATE_LIMITED` with `Retry-After` | Guessing references is throttled; successful lookups never count |
| 9 (optional) | restart with `AIRLINE_SEAT_HOLD_ENABLED=true` (see Run), repeat step 4, then `POST .../{reference}/confirm` | | 201 `HELD`, then 200 `CONFIRMED` | The seat hold is a flag and a Strategy, off by default (ADR 0003) |

### Errors
Every error is an RFC 9457 problem (`application/problem+json`) with a machine-readable `code`
and the request id, also returned in the `X-Request-Id` response header. Bodies never contain
stack traces, SQL, or class or constraint names.
```json
{ "type": "about:blank", "title": "Conflict", "status": 409, "detail": "Seats already booked: 12B",
  "instance": "/api/v1/bookings", "code": "SEAT_UNAVAILABLE",
  "requestId": "3f9c1e0a-7b2d-4c55-9a51-0d6e2b8f4a17", "unavailableSeats": ["12B"] }
```
Status policy: 400 for invalid input, 404 for unknown ids, 409 for conflicts with the current
state, 422 for an `Idempotency-Key` reused with a different request, 503 with `Retry-After` for
temporary conditions where nothing was changed: `LOCK_TIMEOUT`
under extreme contention, `RETRY_LATER` when the connection pool is exhausted or two bookings
happen to draw the same reference at the same instant. Clients retrying a 503 should add random
jitter to `Retry-After` and cap the number of retries, so they don't all come back together.
429 `RATE_LIMITED` is different: it is not about load. The booking reference is the only credential
for lookup, confirm and cancel, so guessing it must stay slow: a client that collects more than 10
`BOOKING_NOT_FOUND` answers in a minute is refused on those three endpoints until the minute ends
(`Retry-After` says how long). Successful requests never count, search and booking are never
throttled, and the limit is per application instance, keyed by client address (behind a reverse
proxy, set `server.forward-headers-strategy` so that is the real client). It is a small
hand-written class rather than a library limiter because it counts outcomes (404s), not requests,
which Resilience4j and Bucket4j do not do on their own. Full catalogue:
[LLD §6](lld/design.md#6-error-handling).

---

## Design Decisions

The key choices; each has a decision record with the alternatives that were rejected.

| Decision | Choice | Record |
| --- | --- | --- |
| Flight instances | **Materialised**: one row per operating date, 365 days ahead, kept filled by an idempotent daily/startup job. Seats are not stored per flight; only taken seats have rows | [ADR 0001](docs/adr/0001-materialised-flight-instances.md) |
| Concurrency | **Pessimistic lock on the flight row** for every write, backed by a **partial unique index** on live seats. READ COMMITTED; 3 s lock timeout | [ADR 0002](docs/adr/0002-concurrency-strategy.md) |
| Availability | A counter on the flight, changed only under the lock; invariant tested | [HLD §7](hld/architecture.md#7-seat-availability) |
| Cancellation | Soft release (status change); idempotent; same lock order as booking | [HLD §4.5](hld/architecture.md#45-cancellation) |
| Seat hold | Optional, behind a flag, as a Strategy; lazy expiry, no scheduler | [ADR 0003](docs/adr/0003-seat-hold-behind-a-flag.md) |
| Code structure | Package by feature, layered inside; one-way feature dependencies enforced by ArchUnit | [ADR 0004](docs/adr/0004-package-by-feature.md) |
| Authentication | None (out of scope); admin under `/admin`; bookings by unguessable reference | [ADR 0005](docs/adr/0005-no-authentication.md) |
| Safe retries | Optional `Idempotency-Key` on booking creation, stored on the booking with a request fingerprint, looked up under the flight lock; same request → same booking, different request → 422 | [ADR 0007](docs/adr/0007-idempotency-key-on-booking-creation.md) |
| Database | PostgreSQL 16 + Flyway; engine-specific features listed with MySQL equivalents | [ADR 0006](docs/adr/0006-postgresql-and-engine-specific-features.md) |
| Errors | RFC 9457 ProblemDetail + `code` + `requestId` on every error | [LLD §6](lld/design.md#6-error-handling) |
| Time | UTC everywhere; an injected `Clock`, so tests control time | [HLD §9](hld/architecture.md#9-major-design-decisions) |

---

## Search Algorithm

**How flights are identified.** A bookable flight is a `flight_instance` row: one schedule on one
UTC date. Its `id` is the `flightInstanceId` the client uses for the seat map and booking.
`(schedule_id, flight_date)` is unique.

**How schedules are matched.** At generation time, not at search time. The generator walks every
date in the window and emits an instance only when the date's UTC day of week is one of the
schedule's operating days. Search therefore contains no day-of-week logic: it is one indexed
query (`idx_instance_search`):
```sql
SELECT … FROM flight_instance
WHERE origin_code = ? AND destination_code = ? AND flight_date = ? AND departure_at > now
ORDER BY departure_at
```
Before querying, the service checks:
- the airport codes are well-formed and the airports exist
- origin and destination differ
- the date is inside [today, today + 365]

Flights that have already departed today are hidden. A non-operating date returns `200 []`.

**How flight instances are generated.**
1. When a schedule is created, all instances for [today, today + booking window] are generated in
   the same transaction, by a pure function (`FlightInstanceGenerator`) and one JDBC batch insert.
2. Times are UTC. An arrival at or before the departure time means the next day.
3. The rolling window is kept filled by the same top-up, triggered daily (cron), once at startup
   (to heal downtime), and on demand through `POST /admin/instance-window/extend` (e.g. after
   lengthening the window). It re-generates the window for every schedule and inserts with
   `ON CONFLICT (schedule_id, flight_date) DO NOTHING`, so re-runs, gaps, overlapping triggers
   and several nodes are all safe. Instances are generated one day past the bookable window, so
   the newest bookable date already exists when the window moves at midnight, before the daily
   job has run. A startup run that fails is logged and left to the daily job; it never stops the
   application from starting.

**How seat availability is computed.** Each instance keeps an `available_seats` counter. It is
decremented by a booking and incremented by a cancellation, always inside the transaction that
holds that flight's lock, so it is exact. A database CHECK keeps it between 0 and the total, and
tests assert `available_seats = total_seats − taken seat rows`. Search reads the counter
directly, with no aggregation. With the seat hold enabled, seats on expired-but-not-yet-released
holds are added back with one grouped query.

The seat map is the aircraft's seat layout (rows × letters, in configured order) minus the taken
seats (`ACTIVE`, or `HELD` with a live hold).

---

## Booking Algorithm

**Seat validation**, in three layers before anything is written:
1. **Request shape** (Bean Validation): a flight id, 1+ passengers, a non-blank name (max 120)
   and a seat number for each.
2. **Rules without the database** (`BookingRequestValidator`):
   - at most 9 passengers
   - seat numbers trimmed and upper-cased
   - format `^[1-9]\d?[A-Z]$`
   - no seat twice in one request
3. **Rules that need the flight**, checked under the lock:
   - the flight exists, has not departed, and is inside the booking window
   - every seat exists on that aircraft (`SeatLayout`, the single definition of a valid seat)
   - no requested seat is taken

**Seat locking strategy.** We lock the **flight**, not individual seats: `SELECT … FOR UPDATE` on
the `flight_instance` row is the first thing every write does. Bookings on one flight therefore
run one at a time, and bookings on different flights run in parallel. The partial unique index
`uq_active_seat (flight_instance_id, seat_number) WHERE status IN ('ACTIVE','HELD')` is the
database-level backstop: even buggy code could not store a seat twice. See
[ADR 0002](docs/adr/0002-concurrency-strategy.md).

**Transaction flow** (one transaction, `BookingService.createBooking`):
```
validate request  →  LOCK flight row  →  [seat hold: release expired holds]
→  departed? (409)  →  beyond the booking window? (400)  →  seats on aircraft? (400 INVALID_SEAT)  →  any seat taken? (409 SEAT_UNAVAILABLE)
→  generate reference  →  insert booking + one booking_seat per passenger  →  counter −= n  →  COMMIT
```
Any failure rolls back everything, so a booking is **all or nothing**: if one requested seat is
taken, none is booked, and the 409 lists the taken seats.

**Booking creation.**
- The reference is a 6-character PNR from an alphabet without look-alike characters (no
  0/O/1/I), made with `SecureRandom`. It is random rather than sequential because it alone opens
  the booking (sequential codes could be guessed). It is checked for uniqueness, and the unique
  constraint is the final guard: if two bookings draw the same code at the same instant, the second
  gets 503 `RETRY_LATER` and nothing is booked ([ADR 0005](docs/adr/0005-no-authentication.md)).
- The booking is `CONFIRMED` with `ACTIVE` seats (or `HELD` with the seat hold on).
- The response carries everything the brief lists: reference, flight number, flight date,
  passenger count, seats with names, and status.

**Concurrency handling.**
- **Exact seat check:** the seat check runs after the lock is granted, under READ COMMITTED, so it
  sees every booking the previous lock holder committed.
- **No deadlocks:** one lock per transaction, always taken first.
- **Fail fast:** a request that cannot get the lock within 3 s gets 503 `LOCK_TIMEOUT` with
  `Retry-After: 1`.
- **Proven by tests:**
  - 50 threads booking one seat: **exactly 1 succeeds**, 49 get `SEAT_UNAVAILABLE`
  - 50 threads booking 50 seats: all succeed
  - overlapping 1A+1B vs 1B+1C: exactly 1 succeeds, never a partial booking
- **Shown to fail without the guard:** with the lock and the index removed, **10 of 50**
  succeeded.

---

## Cancellation Algorithm

**Booking lookup.** By booking reference. Only the flight id is read first, without loading the
booking. That's deliberate: the booking must be loaded after the lock, or two simultaneous
cancellations could both see it as confirmed.

**Transaction flow** (one transaction, `BookingService.cancelBooking`):
```
find flight id by reference (404 if unknown)  →  LOCK flight row  →  [seat hold: release expired holds]
→  load booking + seats  →  already CANCELLED/EXPIRED? return it unchanged (200)
→  flight departed? (409 BOOKING_NOT_CANCELLABLE)
→  booking CANCELLED, seats RELEASED  →  counter += n  →  COMMIT
```
The lock order is the same as booking's, so cancellation and booking can never deadlock.

**Seat release.** Seats are not deleted: each `booking_seat` row becomes `RELEASED`, with
`released_at`. The unique index covers only `ACTIVE`/`HELD` rows, so the seat is bookable again
immediately, and the history stays. The seats go back to the flight's counter.

**Status updates.**
- Booking: `CONFIRMED → CANCELLED` (with `cancelled_at`), through the state machine in
  `BookingStatus`; an illegal transition is impossible.
- Each seat: `ACTIVE → RELEASED`.
- Cancelling again returns the current state with 200 and changes nothing. Ten simultaneous
  cancellations release the seats exactly once (tested).
- The one cancellation rule, "before departure", is a single check in `BookingService`. If a
  second rule arrives, it becomes a Strategy like `BookingPolicy`; not before.

---

## Assumptions

**From the assignment brief**
- The system serves a single airline.
- Airports and aircraft are preloaded through seed data; their CRUD operations are out of scope.
- The seat map is fixed for each aircraft.
- Flight schedules do not change after creation.
- A booking covers a single flight instance, and a seat belongs to only one booking.
- All timestamps are stored in UTC; time-zone conversion is out of scope.
- Payment and refund processing are out of scope.

**Additional assumptions**
1. **Schedule times:** departure and arrival times entered through the admin API are interpreted
   as UTC.
2. **Overnight flights:** an arrival time earlier than the departure time means arrival on the
   following calendar day. Flights longer than 24 hours are out of scope.
3. **Booking window:** flights are bookable from the current UTC date up to and including 365
   days ahead; the window advances daily.
4. **Authentication:** user accounts and authentication are out of scope. Administrative
   endpoints are separated under `/admin` and would be protected by role-based access in
   production. Bookings are retrieved and cancelled using the booking reference.
5. **Seat hold:** booking is a single step and seats are confirmed immediately, as described in
   the brief. An optional seat hold can be enabled through configuration
   (`airline.seat-hold.enabled`, default `false`; `airline.seat-hold.ttl`). When enabled, seats
   are held until confirmation or expiry; expired holds are released when availability is next
   checked. A scheduled clean-up job is not included.
6. **Double booking:** a seat on a flight instance cannot be assigned to more than one active
   booking. The same passenger name may appear on more than one booking, as passenger
   validation is not required.
7. **Passengers:** each seat is assigned to one passenger, identified by name only. A booking
   contains at most 9 passengers.
8. **Departed flights:** flights that have already departed cannot be booked or cancelled.
9. **Cancellation:** cancellation applies to the whole booking; partial cancellation is out of
   scope. Cancelling an already cancelled booking returns its current state.
10. **Seat map:** the seat map reflects the committed booking state at the time of the request;
    push updates are out of scope.
11. **Scale:** the design targets a few hundred daily schedules (roughly 100,000 flight
    instances a year). Application instances are stateless and can be scaled horizontally, with
    the database as the source of truth for seat inventory.
12. **Flight numbers:** each flight number identifies exactly one schedule.
13. **Schedule validity:** a schedule takes effect from its creation date and has no end date.
14. **Seat selection:** seats are chosen explicitly by the customer; there is no
    auto-assignment.
15. **Aircraft rotation:** conflicts from one aircraft operating two overlapping flights are not
    validated.

---

## Design principles and patterns in practice

| Principle or pattern | Where it shows | Deliberately not done |
| --- | --- | --- |
| Separation of concerns / layering | Controllers map HTTP only; services own use cases and transactions; repositories persist only. Enforced by `ArchitectureTest` | No hexagonal ports and adapters, no separate persistence model |
| Single source of truth (DRY) | `SeatLayout` alone defines a valid seat; `SeatOccupancyQueries.takenSeats` alone defines a taken seat (seat map and booking); `ErrorCode` holds every code and status | No duplicated seat or availability rules across features |
| Encapsulation, behaviour on entities | `Booking.cancel/confirm/expireHold`, `FlightInstance.reserve/release/isDepartedAt`, `FlightSchedule.operatesOn` | No public setters; no anaemic entities with logic in services |
| State machine (enum) | `BookingStatus.canTransitionTo`; every transition goes through it | No status setter; no framework state machine |
| Strategy | `BookingPolicy` (immediate or seat hold, chosen once in `BookingPolicyConfig`) | No interface for `PnrGenerator`, the cancellation rule or services: each has one implementation |
| Value object | `SeatLayout` record (validated, immutable) | Seat labels and references stay `String`; a type would add mapping for no rule |
| Static factory methods | `Booking.confirmed/held`, `FlightSchedule.create`, `FlightInstance.scheduled` | No builders in production code |
| Tell, don't ask (Law of Demeter) | `aircraft.seatLayout()`, `schedule.operatesOn(day)`, `flight.isDepartedAt(now)` | No getter chains |
| KISS / YAGNI | One `BookingRequestValidator` with one private method per rule; result records double as response bodies | No rule engine, mapper layer, cache, event bus or scheduler |
| Defence in depth | Service checks first; database constraints (unique, check, partial index) as the last guard, mapped to clean 409s | No reliance on application code alone for correctness |
| Testability via injected time | `Clock` bean; tests use a controllable clock | No `Instant.now()` in business code |
| Fail fast | `lock_timeout` → 503 with `Retry-After`; validation before the transaction | No unbounded waiting on locks |
| Factory via configuration | `BookingPolicyConfig` chooses the `BookingPolicy` implementation once from `airline.seat-hold.enabled`; the service never looks at the flag | No abstract factory, no registry: two products, one switch |
| Interceptor | `BookingLookupThrottle` (`HandlerInterceptor`) guards the reference endpoints by route; `BookingWebConfig` registers it | Not a servlet filter: a filter matches paths by string and can be bypassed by path variants |
| Filter chain | `RequestIdFilter` puts a request id on every request, response and log line | No tracing framework for a single service |
| Repository | Spring Data interfaces per aggregate; one native query class (`SeatOccupancyQueries`) where SQL is clearer than JPQL | No generic DAO layer, no query objects |
| Dependency injection | Constructor injection, `final` fields, no field injection; `Clock` and policies are beans | No service locator, no static access |
| Idempotent operations | Cancel returns the same result when repeated; instance-window top-up inserts only what is missing (`ON CONFLICT DO NOTHING`); `POST /bookings` with an `Idempotency-Key` returns the booking it created on a retry (ADR 0007) | No stored-response cache: the replay is built from the live booking row |
| Soft release | A cancelled seat row becomes `RELEASED`, never deleted; the partial index ignores it | No hard deletes, so history and audit stay intact |

Patterns the planned extensions would bring, each at a seam that already exists; none is in the
code today because its feature is not:

| Pattern | Feature that needs it | Where it attaches |
| --- | --- | --- |
| Strategy (second use) | Cancellation rules (cut-off, fees, admin override) or fares | `CancellationPolicy` replacing the one check in `BookingService`; a `PricingPolicy` on the schedule, like `BookingPolicy` today |
| Decorator | Demand-based pricing on top of a base fare | A `PricingPolicy` wrapping another and scaling by occupancy; the booking path keeps calling one interface |
| Observer / domain events | Waitlist notifications, confirmation e-mails | Spring `ApplicationEventPublisher` from the cancellation and confirmation paths; listeners after commit (`@TransactionalEventListener`), with an outbox table once delivery must survive a crash |
| Specification | Search with more filters (time of day, stops, aircraft) | Composable predicates over `flight_instance` instead of a growing list of query methods |
| Adapter / port | Payment gateway, notification provider | One interface per external system with the real and a fake implementation; the service depends on the interface only |
| Chain of responsibility | Many booking validation rules with ordering and short-circuit | `BookingRequestValidator` split into ordered rule objects, only when the single class stops reading well |
| Saga / command | Asynchronous booking (202, queue, confirm) | A booking command with compensation (release) on failure; the per-flight lock still decides who wins |
| Circuit breaker, rate limiter | Backpressure on writes as load requires | Resilience4j or Bucket4j around `BookingService`, or the gateway in front (HLD §6) |

---

## Scaling and evolution

None of this is built. Each step is paired with the signal that would justify it.

| Step | Trigger |
| --- | --- |
| Run several application instances behind a load balancer | Already possible: the service is stateless and correctness lives in the database. Do it when one node's CPU or latency is the limit |
| Serve search and seat map from a read replica or a short-TTL cache | Read traffic dominates; accept slightly stale availability and keep booking on the primary |
| Partition `flight_instance` and `booking_seat` by date; archive departed flights | Tables reach tens of millions of rows or index upkeep slows writes |
| Scheduled sweep of expired holds | The seat hold is on and flights with stale holds see few writes (reporting lags) |
| Finer-grained locking (per seat) | Measured lock waits on a single very popular flight |
| A `CancellationPolicy` Strategy (cut-off window, admin override, fees), like `BookingPolicy` | The business defines a second cancellation rule; today the one rule is a single check in `BookingService` |
| Partial cancellation | Customers ask to drop one passenger; seat-level status already supports it, no migration |
| Surname check with the reference on lookup and cancel | Self-service is exposed publicly without accounts |
| Authentication and role-based access on `/admin` | Any deployment beyond a demo |
| Aircraft-rotation check (same aircraft on overlapping flights, with turnaround time) | Schedules are planned in this system rather than imported from a fleet-planning tool |
| Cluster-wide lock for the daily job (e.g. ShedLock), so one node runs it | Many nodes each repeating the (idempotent) daily run becomes costly |
| Distributed tracing (OpenTelemetry via Micrometer Tracing; `traceId` in the log pattern, `traceparent` honoured) | A second service or asynchronous messaging appears. Today a single service is correlated by `requestId`: on every log line (job runs use `job-startup-…`/`job-daily-…`) and in every error response |
| Backpressure on booking writes: rate limiting and a circuit breaker, introduced as load requires. Spring has no built-in; the options are Resilience4j (`@RateLimiter` / `@CircuitBreaker` on the booking service) or Bucket4j inside the service, or Spring Cloud Gateway's `RequestRateLimiter` (Redis-backed, cluster-wide) in front of it | A sale on one flight saturates the connection pool faster than the fail-fast timeouts shed load. Today: lock waits ≤ 3 s, connection waits ≤ 2 s, both answered with a retryable 503 |
| A separate read pool, or a write bulkhead, so search never waits behind bookings | Search latency rises during booking spikes |
| Cluster-wide lookup throttle at the edge (gateway or load balancer) | The in-service throttle on unsuccessful lookups is per instance, so many instances multiply a guesser's budget (ADR 0005) |

Feature extensions follow the same rule: each has a seam in the current code and none changes
the booking transaction.

| Feature | Where it plugs in |
| --- | --- |
| Waitlist when a flight sells out | The cancellation path is the only place seats come back; it would publish "n seats freed on flight X" and a waitlist service would notify the next customers, who book through the normal path (first come, first served, under the same lock) |
| Adjacent seats for a group | `SeatLayout` already knows rows and letters; a pure function over the seat map (longest run of free seats in a row) suggests seats, and the booking path is unchanged because it only receives the final seat list |
| Fares and pricing (none in the brief) | A `PricingPolicy` on the schedule or instance, applied when the booking is created; the booking would record the amount, as it records the seats |
| Refund window on cancellation | The cancellation rule is one check in `BookingService` today; a rule that depends on time to departure is the `CancellationPolicy` Strategy row above |
| Payment step | Turn the seat hold on: hold → pay → confirm; the hold TTL is sized above the payment flow's worst case and the re-check under the lock on confirmation is the backstop (ADR 0003) |

### Measured
`scripts/load-test.sh` (see [Load test](#load-test)) on one laptop (Windows 11, Docker Desktop,
24 logical cores; application, PostgreSQL and JMeter each in a container on the same machine),
65 threads for 60 s against the default pool of 10, at two data sizes. Times are elapsed
milliseconds as JMeter saw them; responses are counted by status and by the error `code` in the
body. The throttle run is reported separately below.

Data set (`load/seed.sql`): schedules on the routes between the 10 seeded airports (all 90
ordered pairs at 100 schedules), one flight per schedule per day for the 365 bookable days;
every flight an A320 with 180 seats (rows 1–30, A–F), 54 of them (30 %) booked before the run in
two-seat bookings, every tenth booking cancelled with its seats released, so about 131 seats are
free on each flight. The hot flight starts with 132 free seats; the race seat is free. Lookups
and cancels draw from 10,000 live references chosen at random.

| | ~100k bookings | ~1M bookings |
| --- | --- | --- |
| Schedules / routes | 10 / 10 | 100 / 90 |
| Flights (one per day per schedule) | 3,650 | 36,500 |
| Bookings before the run (cancelled) | 98,550 (9,855) | 985,500 (98,550) |
| Seat rows | 197,100 | 1,971,000 |
| Table sizes | — | `booking` 177 MB, `booking_seat` 330 MB |

| Scenario (threads) | Bookings in DB | req/s | p50 | p90 | p95 | p99 | max | Responses by status and code |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Search by route and date (20) | 100k | 736 | 22 | 40 | 46 | 75 | 258 | all 200 |
| | 1M | 800 | 22 | 41 | 47 | 79 | 293 | all 200 |
| Lookup by reference (10) | 100k | 390 | 22 | 40 | 46 | 75 | 226 | all 200 |
| | 1M | 416 | 22 | 41 | 46 | 79 | 292 | all 200 |
| Cancel seeded bookings (5), releasing seats while the booking scenarios run | 100k | 168 | 25 | 44 | 50 | 82 | 244 | all 200 (repeats are idempotent) |
| | 1M | 174 | 25 | 44 | 52 | 88 | 300 | all 200 |
| Booking, random flight and seat (10) | 100k | 369 | 24 | 43 | 50 | 81 | 302 | 16,165 × 201; 5,983 × 409 `SEAT_UNAVAILABLE` (27 %, seat already taken) |
| | 1M | 361 | 25 | 44 | 51 | 86 | 356 | 15,790 × 201; 5,854 × 409 `SEAT_UNAVAILABLE` (27 %) |
| Booking, one hot flight (20) | 100k | 616 | 29 | 48 | 57 | 96 | 335 | 138 × 201 (every free seat sold, including seats released by concurrent cancels); 36,839 × 409 `SEAT_UNAVAILABLE` |
| | 1M | 611 | 29 | 48 | 58 | 104 | 416 | 134 × 201; 36,525 × 409 `SEAT_UNAVAILABLE` |
| Race: 5 customers pick the same seat in the same instant (burst, once) | 100k | — | 331 | | | | 331 | **1 × 201; 4 × 409 `SEAT_UNAVAILABLE`** |
| | 1M | — | 370 | | | | 385 | **1 × 201; 4 × 409 `SEAT_UNAVAILABLE`** |

About 2,300 requests/s, no 5xx, no `LOCK_TIMEOUT`, no pool-wait timeout
(`hikaricp.connections.timeout` 0), the raced seat had exactly one active booking, and the
inventory invariant held on all 36,500 flights afterwards, with about 10,000 cancellations
having released seats under the booking traffic. What to read from it:
- Ten times the data changes nothing: every hot query is an index lookup
  ([LLD §11](lld/design.md#11-query-plans-at-scale)), so latency depends on concurrency, not
  on table size.
- The hot flight, where 20 writers queue on one row lock, still answers in 28 ms at p50: the
  lock is held for a few milliseconds per booking, so the queue drains faster than it fills.
- Cancellations release seats on random flights while bookings are being written to the same
  flights, and the counter still matches the seat rows everywhere afterwards: cancel and book
  take the same flight lock in the same order (HLD §6).
- The race is the HTTP version of `BookingConcurrencyTest` at a realistic size: five customers
  click the same seat in the same instant, exactly one wins, the other four get a clean 409
  naming the seat, nothing is stored twice (`RACE=50` for the worst case gives 1 and 49). Its
  ~350 ms is the first second of the run, JVM cold, five writers queued on one lock.
- Fail-fast under a stall: in one of three 1M runs a database checkpoint (PostgreSQL flushing the
  0.5 GB written by the seed) fell into the measured minute and held I/O for about 6 s; 181 of
  120,000 requests (0.15 %) waited more than 2 s for a pooled connection and each got
  503 `RETRY_LATER` within that bound, nothing was changed, the other requests and the
  invariant were unaffected. The seed now ends with `CHECKPOINT` so the flush happens before the
  run; the observation is kept because it is the overload behaviour of [HLD §6](hld/architecture.md)
  seen for real.

**Throttle run** (`bash scripts/load-test.sh throttle`, 2 clients for 20 s): **10 × 404
`BOOKING_NOT_FOUND`, then 60,472 × 429 `RATE_LIMITED`** at ~3,000 req/s, p50 0 ms, p99 2 ms.
A guesser costs nothing: after ten misses every further request is refused by the interceptor
before any database work, and the error says why (`RATE_LIMITED`, `Retry-After`). The count is
exact per client up to the number of requests in flight: the check runs before the request and
the miss is recorded after it, so two parallel guessers can both pass at nine (11 misses in two
of four runs). Per client means per address, which is why this is a separate run (ADR 0005).
- With the seat hold on (`AIRLINE_SEAT_HOLD_ENABLED=true`, 100k run), every booking is created
  `HELD` and the same guarantees hold: one live claim per seat, one winner in the race, the
  invariant intact. Throughput is about 15 % lower (search 603 vs 719 req/s, hot flight 479 vs
  611) because every write also runs the overdue-hold query under the lock, which the default
  path skips entirely.
- These are relative numbers: client, application and database shared one machine, so they
  show the shape (flat with data, bounded under contention), not the capacity of a deployment.

### A sale day on a popular flight

What happens today, from the measurements above: all writes to one flight queue on that flight's
row lock, held for about 2 ms per booking, so one flight absorbs a few hundred bookings per
second (20 concurrent buyers: ~600 req/s, p95 ≈ 58 ms, every free seat sold exactly once, 409
for everyone after that) while other flights, search and lookup are unaffected. A sale day is a
burst of demand that outstrips 180 seats within seconds; from then on the correct answer to the
crowd is a fast 409, which is what they get. The ceiling is concurrency, not correctness: per
instance 10 requests are in the database at once, the rest wait at most 2 s for a connection and
then get 503 `RETRY_LATER` with `Retry-After`, nothing changed. So under a true flash sale the
system **degrades gracefully but does not stretch by itself**. The extensions that make it
stretch, in the order they would be built (none is in the code; each keeps the lock-and-index
core unchanged):

| Extension | What it adds | When |
| --- | --- | --- |
| More instances behind a load balancer, pool size as a setting, PgBouncer in front of PostgreSQL | Linear capacity for everything that is not the one hot flight (the service is stateless; see the pool-sizing ladder in [HLD §6](hld/architecture.md)) | First sign of 503 `RETRY_LATER` from connection waits |
| Separate read pool or a read replica for search and seat map | Readers never wait behind a sale's writers; the seat map stays fast while a flight sells out | Search p95 rises during booking spikes |
| Seat hold (already built, behind `airline.seat-hold.enabled`) | Customers hold seats for 10 minutes while paying, so a sale does not become a race against checkout; expired holds free seats automatically | A payment step exists |
| Virtual waiting room / admission control at the edge (gateway rate limiting, Resilience4j or Bucket4j in the service) | Lets through as many buyers per second as the flight's lock can serve and answers the rest with a queue position or a `Retry-After`, instead of letting them time out | Traffic on one flight exceeds what fail-fast 503s shed cleanly |
| Short-TTL availability cache (`availableSeats` per flight in memory or Redis) | Search and seat-map reads during a sale served without touching PostgreSQL; a few seconds stale is acceptable for display, booking stays exact | Read traffic on hot flights dominates |
| Asynchronous booking: accept (202 + reference) → queue → confirm, per-flight queue consumers | Turns the burst into a smooth stream at the lock's pace; customers poll or are notified; no request ever waits on the lock | Peak writes on one flight exceed the lock's throughput for sustained periods |
| Per-seat locking (`booking_seat` row per seat, locked in a fixed order) | Different seats on the same flight commit in parallel; the flight lock goes | Measured lock waits on a single flight are the limit and the above steps are exhausted |

The order matters: the first two rows are configuration and deployment; the seat hold is a
flag; the waiting room and the cache are additions in front of and beside the service; only the
last two change how a booking is written, and they are the last resort because the current
write path is what makes the guarantee easy to explain.

### Multiple airlines
The brief is single-airline, but the design does not depend on that.

**Already works with no change:**
- The airline code is the first two characters of every flight number (`XY101`, `EK001`), so
  schedules of several airlines can coexist and their flight numbers can never clash.
- Flights of different airlines on the same route at the same time are normal. Nothing should
  prevent them, and search simply returns all of them.
- The flight lock is per flight, so airlines never block each other.
- Instance generation, booking, cancellation and the seat hold are unchanged.

**What would be added, and when:**

| Need | Change | Trigger |
| --- | --- | --- |
| Aircraft belong to an airline; airline data (name, country) | An `airline` reference table (like `airport`) and `aircraft.airline_code`, one additive migration; check that a schedule's flight-number prefix matches its aircraft's airline | A second airline is onboarded |
| Codeshares | Store the **operating** carrier explicitly, because the flight-number prefix is only the marketing carrier | Flights are sold under another airline's number |
| Airlines must not see each other's data | Tenant isolation: an airline id on owned tables, enforced on every query (e.g. PostgreSQL row-level security), and airline-scoped admin authentication | The system is offered as a platform to separate airlines |

Not covered for one airline or many: airport slot or capacity limits (handled by slot
coordination outside this system), and aircraft-rotation clashes (see the table above).

---

## Testing approach

353 tests, all run by `./mvnw verify`. Integration tests use PostgreSQL 16 in Testcontainers with
the real Flyway migrations. No H2, and no test runs inside a test transaction.

| Kind | What it proves | Classes |
| --- | --- | --- |
| Unit, test-first | Seat layout (labels, order, validity); instance generation (operating days only, both window ends, leap day, overnight); booking status transitions (full table); booking and hold behaviour; validators; reference format | `SeatLayoutTest`, `FlightInstanceGeneratorTest`, `FlightScheduleTest`, `BookingStatusTest`, `BookingTest`, `BookingHoldTest`, `FlightInstanceTest`, `BookingRequestValidatorTest`, `PnrGeneratorTest`, policy tests |
| API (MockMvc + real DB) | Every endpoint's happy path; every response field; booking is all or nothing; cancel then rebook; idempotent cancel; window top-up idempotent and gap-filling (job and endpoint); a retry with the same `Idempotency-Key` returns the same booking (also 20 concurrent retries → one booking), the key reused for another request → 422, no key → unchanged behaviour | `ScheduleApiTest`, `FlightSearchApiTest`, `SeatMapApiTest`, `BookingApiTest`, `BookingCancellationApiTest`, `BookingIdempotencyTest`, `InstanceWindowJobTest`, `InstanceWindowApiTest` |
| Concurrency | Exactly one winner for one seat (50 threads); no partial overlap; concurrent cancels release once; cancel-vs-book stays consistent; inventory invariant after every scenario; duplicate-number race gives no 500 | `BookingConcurrencyTest`, `CancellationConcurrencyTest`, `DuplicateFlightNumberRaceTest` |
| Error contract | All 23 reachable error codes have the same shape and leak nothing; lock timeout → 503; pool exhaustion → 503 within the connection timeout; a booking-reference clash → 503 `RETRY_LATER`, nothing stored; the 11th unsuccessful lookup in a minute → 429, other clients and other endpoints unaffected; request ids; job-run ids | `ErrorContractTest`, `LockTimeoutTest`, `PoolExhaustionTest`, `BookingReferenceClashTest`, `LookupThrottleTest`, `LookupMissLimiterTest`, `RequestIdFilterTest`, `GlobalExceptionHandlerTest`, `InstanceWindowJobLogIdTest` |
| Seat hold on | Hold → confirm; expiry frees seats; confirm-vs-expiry race (the clock decides the single winner) | `SeatHoldTest` |
| Edge cases, configuration, schema | Last day of the window, overnight, created late in the day, last free seat, more seats than remain; a 30-day window applied by configuration to generation, search and booking; database-carried rules (seat row must match its booking's flight, seat and flight-number formats) | `EdgeCasesTest`, `BookingWindowConfigTest`, `SchemaIntegrityTest` |
| Architecture | Controllers never reach repositories; nothing depends on the API layer; no package cycles | `ArchitectureTest` |

The single-seat race test was first run **without** the lock and the unique index (10 of 50
bookings succeeded), then with them (exactly 1). The concurrency classes passed 5 runs in a row,
and the full build passed 3 clean runs in a row.

Load and data-growth behaviour is measured rather than unit-tested: [Load test](#load-test) and
[Measured](#measured).

---

## Repository layout

```
├── README.md                 this file
├── hld/                      architecture.md, architecture.pdf, diagrams/ (.mmd sources + .png)
├── lld/                      design.md
├── docs/adr/                 decision records 0001–0007
├── src/main/java/…           the application (packaged by feature)
├── src/main/resources/       application.yml
├── db/                       schema.sql (commented snapshot), migrations/ (Flyway V1–V6)
├── tests/                    java/ (all tests)
├── load/                     load test: seed.sql, airline-load.jmx (JMeter), Compose override for the throwaway stack
├── scripts/smoke-test.sh     end-to-end check against a running stack
├── scripts/load-test.sh      seeded JMeter run on a throwaway stack, removed afterwards
├── DECISIONS.md              one-line log of every decision, by phase
├── Dockerfile, docker-compose.yml
└── pom.xml, mvnw, .mvn/      Maven build (wrapper)
```
The brief's expected structure is followed exactly. `tests/` holds the test sources directly
(configured in `pom.xml`).

## Out of scope
Authentication and authorisation, payments and refunds, airport and aircraft CRUD, schedule
edits or deletes, time-zone conversion, passenger validation, multi-leg itineraries, pricing and
fare classes, a scheduled clean-up job for holds.

## AI assistance
AI tools were used for scaffolding, tests and documentation drafts. Design decisions, the booking
transaction and the concurrency strategy were reviewed, and are explained in
[`docs/adr`](docs/adr/).
