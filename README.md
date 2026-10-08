# Airline Reservation System

Backend for a single airline. A back office defines flight schedules; customers search flights,
view seat maps, book seats and cancel bookings, up to 365 days ahead. Double booking is impossible,
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
./mvnw verify          # compiles and runs all 298 tests against a real PostgreSQL (needs Docker)
```

### Run
```bash
docker compose up --build          # PostgreSQL + the application on http://localhost:8080
```
Then open http://localhost:8080/swagger-ui.html, or check http://localhost:8080/actuator/health.
Stop with `docker compose down -v` (`-v` also deletes the database).

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

### Database initialization
Automatic. On startup Flyway applies [`db/migrations`](db/migrations/):
- `V1`–`V3` create the schema
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
step: create a schedule, search, seat map, book, a seat conflict, cancel twice, rebook.

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
| POST | `/bookings` | Book one or more seats (all or nothing) | 201 + `Location` |
| GET | `/bookings/{reference}` | Fetch a booking | 200 |
| POST | `/bookings/{reference}/cancel` | Cancel; repeating it returns the same result | 200 |
| POST | `/bookings/{reference}/confirm` | Confirm a held booking (seat hold only) | 200 |

### Walkthrough with curl
```bash
# 1. Back office: XY101 Dubai -> London, Mon/Wed/Fri 09:30-13:45 UTC, on the A320
curl -s -X POST localhost:8080/api/v1/admin/schedules -H 'Content-Type: application/json' -d '{
  "flightNumber": "XY101", "origin": "DXB", "destination": "LHR",
  "departureTime": "09:30", "arrivalTime": "13:45", "aircraftId": 1,
  "daysOfOperation": ["MONDAY", "WEDNESDAY", "FRIDAY"]}'
# -> 201 {"id":1, ..., "generatedInstances":157}   (about 157: Mon/Wed/Fri for one year)

# 2. Search a Monday (any date from today up to 365 days ahead)
curl -s 'localhost:8080/api/v1/flights?origin=DXB&destination=LHR&date=2026-11-02'
# -> [{"flightInstanceId":42,"flightNumber":"XY101",...,"availableSeats":180}]

# 3. Seat map
curl -s localhost:8080/api/v1/flights/42/seats

# 4. Book two seats
curl -s -X POST localhost:8080/api/v1/bookings -H 'Content-Type: application/json' -d '{
  "flightInstanceId": 42, "passengers": [
    {"name": "Ayesha Khan", "seatNumber": "12A"}, {"name": "Bilal Khan", "seatNumber": "12B"}]}'
# -> 201 {"bookingReference":"K7M2QX",...,"status":"CONFIRMED"}

# 5. Someone else wants 12B -> 409 SEAT_UNAVAILABLE, unavailableSeats ["12B"]; 12C is not booked either

# 6. Cancel (repeatable); the seats are bookable again at once
curl -s -X POST localhost:8080/api/v1/bookings/K7M2QX/cancel
```
Replace `42` and `K7M2QX` with the values from your responses.

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
state, 503 `LOCK_TIMEOUT` (with `Retry-After`) under extreme contention. Full catalogue:
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
1. When a schedule is created, all instances for [today, today + 365] are generated in the same
   transaction, by a pure function (`FlightInstanceGenerator`) and one JDBC batch insert.
2. Times are UTC. An arrival at or before the departure time means the next day.
3. A job keeps the rolling window filled: daily (cron) and once at startup, to heal downtime. It
   re-generates the window for every schedule and inserts with
   `ON CONFLICT (schedule_id, flight_date) DO NOTHING`, so re-runs, gaps and several nodes are
   all safe.

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
   - the flight exists and has not departed
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
→  departed? (409)  →  seats on aircraft? (400 INVALID_SEAT)  →  any seat taken? (409 SEAT_UNAVAILABLE)
→  generate reference  →  insert booking + one booking_seat per passenger  →  counter −= n  →  COMMIT
```
Any failure rolls back everything, so a booking is **all or nothing**: if one requested seat is
taken, none is booked, and the 409 lists the taken seats.

**Booking creation.**
- The reference is a 6-character PNR from an alphabet without look-alike characters (no
  0/O/1/I), made with `SecureRandom`. It is checked for uniqueness, and the unique constraint is
  the final guard.
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
→  cancellation policy: flight departed? (409 BOOKING_NOT_CANCELLABLE)
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
- Whether a booking may be cancelled is a `CancellationPolicy` (today: before departure). A new
  rule is a new class.

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
| Strategy | `BookingPolicy` (immediate or seat hold, the flag is read once in `BookingPolicyConfig`); `CancellationPolicy` (one rule today, the expected extension point) | No interface for `PnrGenerator` or for services: there is no second implementation |
| Value object | `SeatLayout` record (validated, immutable) | Seat labels and references stay `String`; a type would add mapping for no rule |
| Static factory methods | `Booking.confirmed/held`, `FlightSchedule.create`, `FlightInstance.scheduled` | No builders in production code |
| Tell, don't ask (Law of Demeter) | `aircraft.seatLayout()`, `schedule.operatesOn(day)`, `flight.isDepartedAt(now)` | No getter chains |
| KISS / YAGNI | One `BookingRequestValidator` with one private method per rule; result records double as response bodies | No rule engine, mapper layer, cache, event bus or scheduler |
| Defence in depth | Service checks first; database constraints (unique, check, partial index) as the last guard, mapped to clean 409s | No reliance on application code alone for correctness |
| Testability via injected time | `Clock` bean; tests use a controllable clock | No `Instant.now()` in business code |
| Fail fast | `lock_timeout` → 503 with `Retry-After`; validation before the transaction | No unbounded waiting on locks |

---

## Scaling and evolution

None of this is built. Each step is paired with the signal that would justify it.

| Step | Trigger |
| --- | --- |
| Run several application instances behind a load balancer | Already possible: the service is stateless and correctness lives in the database. Do it when one node's CPU or latency is the limit |
| Serve search and seat map from a read replica or a short-TTL cache | Read traffic dominates; accept slightly stale availability and keep booking on the primary |
| Partition `flight_instance` and `booking_seat` by date; archive departed flights | Tables reach tens of millions of rows or index upkeep slows writes |
| Idempotency key on `POST /bookings` | Clients retry on timeouts and duplicate bookings appear |
| Scheduled sweep of expired holds | The seat hold is on and flights with stale holds see few writes (reporting lags) |
| Finer-grained locking (per seat) | Measured lock waits on a single very popular flight |
| A second `CancellationPolicy` (cut-off window, admin override, fees) | The business defines a cancellation rule other than "before departure" |
| Partial cancellation | Customers ask to drop one passenger; seat-level status already supports it, no migration |
| Surname check with the reference on lookup and cancel | Self-service is exposed publicly without accounts |
| Authentication and role-based access on `/admin` | Any deployment beyond a demo |
| Aircraft-rotation check (same aircraft on overlapping flights, with turnaround time) | Schedules are planned in this system rather than imported from a fleet-planning tool |

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

298 tests, all run by `./mvnw verify`. Integration tests use PostgreSQL 16 in Testcontainers with
the real Flyway migrations. No H2, and no test runs inside a test transaction.

| Kind | What it proves | Classes |
| --- | --- | --- |
| Unit, test-first | Seat layout (labels, order, validity); instance generation (operating days only, both window ends, leap day, overnight); booking status transitions (full table); booking and hold behaviour; validators; reference format | `SeatLayoutTest`, `FlightInstanceGeneratorTest`, `FlightScheduleTest`, `BookingStatusTest`, `BookingTest`, `BookingHoldTest`, `FlightInstanceTest`, `BookingRequestValidatorTest`, `PnrGeneratorTest`, policy tests |
| API (MockMvc + real DB) | Every endpoint's happy path; every response field; booking is all or nothing; cancel then rebook; idempotent cancel; window job idempotent and gap-filling | `ScheduleApiTest`, `FlightSearchApiTest`, `SeatMapApiTest`, `BookingApiTest`, `BookingCancellationApiTest`, `InstanceWindowJobTest` |
| Concurrency | Exactly one winner for one seat (50 threads); no partial overlap; concurrent cancels release once; cancel-vs-book stays consistent; inventory invariant after every scenario; duplicate-number race gives no 500 | `BookingConcurrencyTest`, `CancellationConcurrencyTest`, `DuplicateFlightNumberRaceTest` |
| Error contract | All 21 reachable error codes have the same shape and leak nothing; lock timeout → 503; request ids | `ErrorContractTest`, `LockTimeoutTest`, `RequestIdFilterTest`, `GlobalExceptionHandlerTest` |
| Seat hold on | Hold → confirm; expiry frees seats; confirm-vs-expiry race (the clock decides the single winner) | `SeatHoldTest` |
| Edge cases | Last day of the window, overnight, created late in the day, last free seat, more seats than remain | `EdgeCasesTest` |
| Architecture | Controllers never reach repositories; nothing depends on the API layer; no package cycles | `ArchitectureTest` |

The single-seat race test was first run **without** the lock and the unique index (10 of 50
bookings succeeded), then with them (exactly 1). The concurrency classes passed 5 runs in a row,
and the full build passed 3 clean runs in a row.

---

## Repository layout

```
├── README.md                 this file
├── hld/                      architecture.md, architecture.pdf, diagrams/ (.mmd sources + .png)
├── lld/                      design.md
├── docs/adr/                 decision records 0001–0006
├── src/main/java/…           the application (packaged by feature)
├── src/main/resources/       application.yml
├── db/                       schema.sql (commented snapshot), migrations/ (Flyway V1–V4)
├── tests/                    java/ (all tests), resources/
├── scripts/smoke-test.sh     end-to-end check against a running stack
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
