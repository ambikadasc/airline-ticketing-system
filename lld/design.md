# Airline Reservation System – Low-Level Design

Companion to [`hld/architecture.md`](../hld/architecture.md). The schema is in
[`db/schema.sql`](../db/schema.sql) and the ER diagram in
[`hld/diagrams/er.png`](../hld/diagrams/er.png).

## 1. Modules and package layout

Base package `com.airline.reservation`, packaged by feature; inside a feature, by layer.

```
com.airline.reservation
├── AirlineReservationApplication      main(): TimeZone UTC, @EnableScheduling
├── common/
│   ├── config/       ClockConfig, AirlineProperties
│   ├── error/        ApiException (+ subclasses that carry extra fields), ErrorCode, GlobalExceptionHandler
│   └── logging/      RequestIdFilter
├── airport/          Airport, AirportRepository
├── aircraft/         Aircraft, SeatLayout, AircraftRepository
├── schedule/
│   ├── api/          AdminScheduleController, CreateScheduleRequest
│   ├── service/      ScheduleService, FlightInstanceGenerator, CreateScheduleCommand, ScheduleResult
│   ├── domain/       FlightSchedule
│   ├── persistence/  FlightScheduleRepository
│   └── job/          InstanceWindowJob
├── flight/
│   ├── api/          FlightController
│   ├── service/      FlightSearchService, SeatMapService, FlightSearchResult, SeatMapResult
│   ├── domain/       FlightInstance
│   └── persistence/  FlightInstanceRepository, FlightInstanceBulkWriter, SeatOccupancyQueries
└── booking/
    ├── api/          BookingController, CreateBookingRequest
    ├── service/      BookingService, BookingRequestValidator, PnrGenerator,
    │                 CreateBookingCommand, BookingResult
    ├── service/policy/  BookingPolicy, ImmediateConfirmationPolicy, SeatHoldPolicy,
    │                    BookingPolicyConfig,
    │                    CancellationPolicy, BeforeDepartureCancellationPolicy
    ├── domain/       Booking, BookingSeat, BookingStatus, SeatStatus
    └── persistence/  BookingRepository
```

**Dependency direction (no cycles; checked by ArchUnit):**
`booking → flight → {aircraft, airport}`, `schedule → {flight, aircraft, airport}`, everything
→ `common`. Within a feature: `api → service → domain, persistence`; nothing depends on `api`.

Two placement choices follow from the no-cycles rule:
- `FlightInstanceGenerator` and `InstanceWindowJob` live in `schedule`. They turn schedules
  into instances; if they lived in `flight`, `flight` would depend on `schedule` while
  `ScheduleService` depends on `flight`.
- The seat map and search read `booking_seat` with SQL in `flight.persistence.SeatOccupancyQueries`
  rather than calling `booking` code, because `booking` already depends on `flight` (it locks
  and updates `FlightInstance`).

**Result records double as response bodies.** Services return immutable `*Result` records that
controllers return as JSON. There is no second mapping layer: the use-case output and the API
shape are the same today. Request records stay in `api` and map to `*Command` records in
`service` with a `toCommand()` method.

## 2. Classes and responsibilities

### common
| Class | Responsibility |
| --- | --- |
| `ClockConfig` | `@Bean Clock clock()` → `Clock.systemUTC()`. Tests replace it with a fixed `@Primary` clock |
| `AirlineProperties` | `@ConfigurationProperties("airline")` record: `bookingWindowDays` (365), `maxSeatsPerBooking` (9), `instanceJobCron`, `seatHold { enabled=false, ttl=PT10M }` |
| `ApiException` | Concrete `RuntimeException(ErrorCode, detail)`. The `ErrorCode` already carries the HTTP status, so one class covers every not-found, conflict and validation case: `new ApiException(ErrorCode.AIRPORT_NOT_FOUND, "Airport not found: XXX")` |
| Subclasses | Only where a response carries extra fields: `SeatUnavailableException` (`unavailableSeats`), `InvalidSeatException` (`invalidSeats`) |
| `ErrorCode` | Enum of every code with its HTTP status (§6) |
| `GlobalExceptionHandler` | `@RestControllerAdvice` extending `ResponseEntityExceptionHandler`; the single place errors become responses |
| `RequestIdFilter` | Reads `X-Request-Id` and keeps it only if it matches `^[A-Za-z0-9._-]{1,64}$` (no log injection), otherwise generates a UUID; puts it in the MDC as `requestId`; echoes it in the response header; clears the MDC afterwards. The log pattern prints it on every line |

### airport, aircraft
| Class | Responsibility |
| --- | --- |
| `Airport` | Entity, read-only (code, name, city, country) |
| `Aircraft` | Entity, read-only; `seatLayout()` returns its `SeatLayout`; `totalSeats()` |
| `SeatLayout` | Record `(int rowCount, String seatLetters)`. `List<String> seats()`: 1A..NZ in row order, then letter order. `boolean contains(String seat)`: parses row and letter and checks bounds. `int totalSeats()`. The single definition of what a valid seat is, used by the seat map and booking |

### schedule
| Class | Responsibility |
| --- | --- |
| `FlightSchedule` | Entity. Static factory `create(flightNumber, origin, destination, departureTime, arrivalTime, aircraftId, days, createdAt)` derives `arrivalDayOffset` (1 if arrival ≤ departure, else 0). `operatesOn(DayOfWeek)` answers the generator. `daysOfOperation` is a `Set<DayOfWeek>` `@ElementCollection` on `flight_schedule_day`. No setters (schedules are immutable) |
| `FlightInstanceGenerator` | Pure logic, no Spring or DB. `static List<FlightInstance> generate(FlightSchedule, SeatLayout, LocalDate from, LocalDate to)`: one instance per date in `[from, to]` whose day of week is an operating day; `departure_at = date + departureTime (UTC)`, `arrival_at = date + offset + arrivalTime (UTC)` |
| `ScheduleService` | `createSchedule(command)`, `getSchedule(id)`, `extendInstanceWindow()` (§7) |
| `InstanceWindowJob` | `@Scheduled(cron = "${airline.instance-job-cron}", zone = "UTC")` and `ApplicationRunner`; both call `ScheduleService.extendInstanceWindow()` and log the inserted count |
| `AdminScheduleController` | `POST /api/v1/admin/schedules`, `GET /api/v1/admin/schedules/{id}` |

### flight
| Class | Responsibility |
| --- | --- |
| `FlightInstance` | Entity. `reserve(int n)` (throws if fewer than `n` available), `release(int n)`, `isDepartedAt(Instant now)`. Holds `scheduleId`/`aircraftId` as plain `Long` (no JPA associations across aggregates). Static factory `scheduled(...)` used by the generator |
| `FlightInstanceRepository` | Spring Data. `findByIdForUpdate(id)` (`@Lock(PESSIMISTIC_WRITE)`, JPQL); search query by origin, destination, date, `departure_at > now`, ordered by departure |
| `FlightInstanceBulkWriter` | `JdbcTemplate.batchUpdate` of `INSERT ... ON CONFLICT (schedule_id, flight_date) DO NOTHING`; returns rows actually inserted |
| `SeatOccupancyQueries` | Read-only SQL over `booking_seat`/`booking` (`NamedParameterJdbcTemplate`): `takenSeats(instanceId, now)` for the seat map; `seatsOnOverdueHolds(instanceIds, now)`, one grouped count for search (§8) |
| `FlightSearchService` | `search(origin, destination, date)`: origin ≠ destination, date inside the window, airports exist; then the indexed search plus the overdue-hold count |
| `SeatMapService` | `getSeatMap(flightInstanceId)`: walks `SeatLayout.seats()` against `takenSeats`; `availableSeats` = total − taken in the same response. Departed flights still have a seat map |
| `FlightController` | `GET /api/v1/flights`, `GET /api/v1/flights/{id}/seats` |

### booking
| Class | Responsibility |
| --- | --- |
| `Booking` | Aggregate root; owns its `BookingSeat`s (`@OneToMany(mappedBy, cascade = ALL)`). Factories `confirmed(reference, instanceId, passengers, now)` and `held(reference, instanceId, passengers, now, expiresAt)`. Transitions `confirm(now)`, `cancel(now)` → seats released, `expireHold(now)` → seats released. `effectiveStatus(now)` (HELD past expiry reads as EXPIRED). Each transition checks the current state via `BookingStatus` |
| `BookingSeat` | Child entity (seat number, passenger name, status, timestamps); `activate()`, `release(now)`; package-private, changed only through `Booking` |
| `BookingStatus` | Enum `HELD, CONFIRMED, CANCELLED, EXPIRED` with `canTransitionTo(target)` (§3) |
| `SeatStatus` | Enum `ACTIVE, HELD, RELEASED` |
| `BookingRepository` | `findFlightInstanceIdByReference(ref)` (returns `Optional<Long>`, no entity load), `findWithSeatsByReference(ref)` (join fetch), `findOverdueHolds(instanceId, now)`, `existsByReference(ref)`. The seat-conflict check does not live here: it reuses `SeatOccupancyQueries.takenSeats` (flight), the same "taken" rule as the seat map |
| `BookingPolicy` | Interface, the Strategy: `Booking newBooking(String reference, long flightInstanceId, List<PassengerSeat> passengers, Instant now)` |
| `ImmediateConfirmationPolicy` | Returns `Booking.confirmed(...)` |
| `SeatHoldPolicy` | Returns `Booking.held(..., now.plus(ttl))` |
| `BookingPolicyConfig` | `@Bean BookingPolicy` chosen by `airline.seat-hold.enabled`; **the only place the flag is read** |
| `CancellationPolicy` | Interface, the Strategy for "may this booking be cancelled now?": `void verifyCancellable(Booking booking, FlightInstance flight, Instant now)`; throws `409 BOOKING_NOT_CANCELLABLE` if not. Called under the flight lock, after the booking is loaded |
| `BeforeDepartureCancellationPolicy` | The only implementation today (`@Component`): cancellable while `flight.isDepartedAt(now)` is false. A new rule (cut-off window, admin override) is a new implementation; locking, transaction and schema are untouched |
| `BookingRequestValidator` | One public `validate(command)`; one private method per rule (§5). Returns the normalised seat list |
| `PnrGenerator` | 6 characters from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (no 0/O/1/I) via `SecureRandom`. A plain class, no interface: there is no second implementation |
| `BookingService` | `createBooking`, `getBooking`, `confirmBooking`, `cancelBooking` (§7) |
| `BookingController` | `POST /api/v1/bookings`, `GET /api/v1/bookings/{reference}`, `POST .../{reference}/confirm`, `POST .../{reference}/cancel` |

## 3. State machines

```
BookingStatus                         SeatStatus (booking_seat)
  (new) ─► CONFIRMED  [hold off]        (new) ─► ACTIVE    [booking CONFIRMED]
  (new) ─► HELD       [hold on]         (new) ─► HELD      [booking HELD]
  HELD ─► CONFIRMED   confirm()         HELD   ─► ACTIVE   booking confirmed
  HELD ─► EXPIRED     expireHold()      HELD   ─► RELEASED booking expired / cancelled
  HELD ─► CANCELLED   cancel()          ACTIVE ─► RELEASED booking cancelled
  CONFIRMED ─► CANCELLED cancel()
  CANCELLED, EXPIRED: terminal
```

Diagram: [`hld/diagrams/booking-state.png`](../hld/diagrams/booking-state.png). An illegal
transition throws `IllegalStateException`. Services check the state first and turn the expected
cases into API errors (§6), so an `IllegalStateException` reaching the handler means a bug and
maps to 500.

## 4. API reference

Base path `/api/v1`, JSON, times in UTC (ISO-8601). Every endpoint has an OpenAPI `@Operation`
summary; Swagger UI at `/swagger-ui.html`.

| Method | Path | Purpose | Success |
| --- | --- | --- | --- |
| POST | `/admin/schedules` | Create a schedule and generate its instances | 201 + `Location` |
| GET | `/admin/schedules/{id}` | Fetch a schedule | 200 |
| GET | `/flights?origin=&destination=&date=` | Search flights | 200 (empty array if none) |
| GET | `/flights/{flightInstanceId}/seats` | Seat map | 200 |
| POST | `/bookings` | Create a booking | 201 + `Location` |
| GET | `/bookings/{reference}` | Fetch a booking | 200 |
| POST | `/bookings/{reference}/confirm` | Confirm a held booking | 200 |
| POST | `/bookings/{reference}/cancel` | Cancel a booking | 200 |

Cancel and confirm are `POST` sub-resources because they are state transitions; the booking stays
retrievable afterwards.

### 4.1 Create schedule
```http
POST /api/v1/admin/schedules
{
  "flightNumber": "XY101",
  "origin": "DXB",
  "destination": "LHR",
  "departureTime": "09:30",
  "arrivalTime": "13:45",
  "aircraftId": 1,
  "daysOfOperation": ["MONDAY", "WEDNESDAY", "FRIDAY"]
}
```
`201 Created`, `Location: /api/v1/admin/schedules/1`
```json
{
  "id": 1,
  "flightNumber": "XY101",
  "origin": "DXB",
  "destination": "LHR",
  "departureTime": "09:30",
  "arrivalTime": "13:45",
  "aircraftId": 1,
  "daysOfOperation": ["MONDAY", "WEDNESDAY", "FRIDAY"],
  "generatedInstances": 157
}
```
`GET /admin/schedules/{id}` returns the same body without `generatedInstances`. The arrival-day
offset is not an input: an arrival at or before the departure time means the next day.

### 4.2 Search flights
```http
GET /api/v1/flights?origin=DXB&destination=LHR&date=2026-11-02
```
```json
[
  {
    "flightInstanceId": 4821,
    "flightNumber": "XY101",
    "origin": "DXB",
    "destination": "LHR",
    "flightDate": "2026-11-02",
    "departureTime": "2026-11-02T09:30:00Z",
    "arrivalTime": "2026-11-02T13:45:00Z",
    "availableSeats": 174
  }
]
```

### 4.3 Seat map
```http
GET /api/v1/flights/4821/seats
```
```json
{
  "flightInstanceId": 4821,
  "flightNumber": "XY101",
  "flightDate": "2026-11-02",
  "aircraftType": "A320",
  "totalSeats": 180,
  "availableSeats": 174,
  "seats": [
    { "seatNumber": "1A", "status": "AVAILABLE" },
    { "seatNumber": "1B", "status": "BOOKED" }
  ]
}
```
Seats are ordered by row, then by the order of letters in the aircraft's configuration.

### 4.4 Create booking
```http
POST /api/v1/bookings
{
  "flightInstanceId": 4821,
  "passengers": [
    { "name": "Ayesha Khan", "seatNumber": "12A" },
    { "name": "Bilal Khan",  "seatNumber": "12B" }
  ]
}
```
`201 Created`, `Location: /api/v1/bookings/K7M2QX`. This body is also returned by GET, confirm
and cancel:
```json
{
  "bookingReference": "K7M2QX",
  "flightInstanceId": 4821,
  "flightNumber": "XY101",
  "flightDate": "2026-11-02",
  "passengerCount": 2,
  "seats": [
    { "seatNumber": "12A", "passengerName": "Ayesha Khan" },
    { "seatNumber": "12B", "passengerName": "Bilal Khan" }
  ],
  "status": "CONFIRMED"
}
```
With the seat hold on, `status` is `HELD` and the body adds `"holdExpiresAt":
"2026-11-01T08:10:00Z"`. The field is omitted for any other status.

### 4.5 Confirm, cancel, fetch
- `POST /bookings/{reference}/confirm`: `HELD` → `CONFIRMED`; already `CONFIRMED` → 200
  unchanged.
- `POST /bookings/{reference}/cancel`: `CONFIRMED`/`HELD` → `CANCELLED`; already `CANCELLED` or
  `EXPIRED` → 200 unchanged.
- `GET /bookings/{reference}`: current state; an overdue `HELD` booking reads as `EXPIRED`.

## 5. Validation

Four layers, each doing only what it is best placed to do:

| Layer | Checks | Error |
| --- | --- | --- |
| Bean Validation on request records / parameters | Required fields; `flightNumber` matches `^[A-Z0-9]{2}\d{1,4}$`; airport codes `^[A-Z]{3}$`; times `HH:mm`; ≥ 1 operating day; `origin ≠ destination` (`@AssertTrue` on the record); passengers non-empty; name non-blank, ≤ 120 chars; seat number non-blank; search `date` parses as ISO date | 400 `VALIDATION_ERROR` with `errors: [{field, message}]` |
| `BookingRequestValidator` (service, before the transaction's first query) | `validatePassengerCount` (1..`maxSeatsPerBooking`); `normaliseSeatNumbers` (trim, upper-case); `validateSeatFormat` (`^[1-9]\d?[A-Z]$`); `rejectDuplicateSeats` | 400 `VALIDATION_ERROR` |
| Services and domain, under the lock | Flight exists; not departed; every seat in the aircraft's `SeatLayout`; seats not taken; booking state allows the transition | 404 / 409 / 400 `INVALID_SEAT` |
| Search and schedule services | Search date within `[today, today + 365]`; airports and aircraft exist; flight number not used | 400 `OUTSIDE_BOOKING_WINDOW`, 404, 409 |
| Database constraints | Unique flight number, unique (schedule, date), unique live seat, unique reference, counter bounds, status values, route, code formats | Last line of defence; the unique ones are mapped to 409 |

If the booking rules grow, the refactor path is a list of rule objects behind one interface;
today one class with one private method per rule is easier to read.

## 6. Error handling

Every error is an RFC 9457 `ProblemDetail` with an added machine-readable `code`:
```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Seats already booked: 12B",
  "instance": "/api/v1/bookings",
  "code": "SEAT_UNAVAILABLE",
  "requestId": "3f9c1e0a-7b2d-4c55-9a51-0d6e2b8f4a17",
  "unavailableSeats": ["12B"]
}
```
Rules that hold for **every** error response, including ones raised by the framework:
- Content type `application/problem+json`; fields `type`, `title`, `status`, `detail`,
  `instance`, plus `code` and `requestId`. `requestId` is the same value as the
  `X-Request-Id` response header, so a client can quote it and support can find the log lines.
- Responses never contain stack traces, SQL, constraint or class names. `detail` is written
  by us; framework messages that can leak internals (Jackson parse errors) are replaced with a
  generic text plus the offending field path.

**400 rather than 422.** Every client-input problem is `400`, whether it is a malformed body or
a well-formed request that breaks a rule (`INVALID_SEAT`, `OUTSIDE_BOOKING_WINDOW`). Clients
branch on `code`, not on the status, so a second 4xx status for "valid syntax, invalid
meaning" adds nothing. The alternative considered was `422 Unprocessable Content` for rule
violations. Conflicts with the current state of a resource (seat taken, flight departed,
duplicate flight number) are `409`.

| HTTP | Code | When |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | Malformed JSON; missing or badly formatted fields/parameters; duplicate seats in one request; origin equals destination; too many passengers |
| 400 | `INVALID_SEAT` | Seat not on the aircraft (lists `invalidSeats`) |
| 400 | `OUTSIDE_BOOKING_WINDOW` | Search date before today or more than 365 days ahead |
| 404 | `AIRPORT_NOT_FOUND`, `AIRCRAFT_NOT_FOUND`, `SCHEDULE_NOT_FOUND`, `FLIGHT_NOT_FOUND`, `BOOKING_NOT_FOUND` | Unknown identifier |
| 404 | `RESOURCE_NOT_FOUND` | No endpoint at that path |
| 405 | `METHOD_NOT_ALLOWED` | Endpoint exists, HTTP method does not (response carries `Allow`) |
| 406 | `NOT_ACCEPTABLE` | `Accept` header excludes JSON |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Request body is not `application/json` |
| 409 | `SEAT_UNAVAILABLE` | A requested seat is taken (lists `unavailableSeats`) |
| 409 | `DUPLICATE_FLIGHT_NUMBER` | A schedule with that flight number exists |
| 409 | `FLIGHT_NOT_BOOKABLE` | Booking or confirming on a departed flight |
| 409 | `BOOKING_NOT_CANCELLABLE` | `CancellationPolicy` refuses (today: the flight has departed) |
| 409 | `HOLD_EXPIRED` | Confirming a hold that has expired (status `EXPIRED`, or `HELD` past `hold_expires_at`) |
| 409 | `BOOKING_NOT_CONFIRMABLE` | Confirming a `CANCELLED` booking |
| 503 | `LOCK_TIMEOUT` | Flight lock not acquired within 3 s; header `Retry-After: 1` |
| 500 | `INTERNAL_ERROR` | Anything unexpected; logged at ERROR with the stack trace, generic message to the client |

`GlobalExceptionHandler` mapping:
- Framework errors (unreadable body, bean/method validation, missing or mistyped parameters,
  unknown path, wrong method, media-type problems) are handled by the inherited
  `ResponseEntityExceptionHandler` methods. One override, `handleExceptionInternal`, then adds
  `code` (chosen from the status: 400 → `VALIDATION_ERROR`, 404 → `RESOURCE_NOT_FOUND`, 405, 406,
  415 as in the table) and `requestId` to all of them, and `errors: [{field, message}]` to
  validation failures.
- `ApiException` → its `ErrorCode` status and code, plus its extra properties.
- `DataIntegrityViolationException` by constraint name: `uq_active_seat` → 409
  `SEAT_UNAVAILABLE`; `uq_schedule_flight_number` → 409 `DUPLICATE_FLIGHT_NUMBER`; anything else
  → 500.
- `PessimisticLockingFailureException` (includes lock timeouts) → 503 `LOCK_TIMEOUT`.
- Any other `Exception` → 500 `INTERNAL_ERROR`.

## 7. Transaction boundaries and lock order

`@Transactional` appears only on service methods. Isolation is the PostgreSQL default, READ
COMMITTED. `spring.jpa.open-in-view=false`, so all loading happens inside the service method.

| Method | Tx | Steps |
| --- | --- | --- |
| `ScheduleService.createSchedule` | read-write | Check airports, aircraft, flight number → `saveAndFlush` schedule (the JDBC insert needs its id) → generate `[today, today+365]` → bulk insert → return with count |
| `ScheduleService.getSchedule` | read-only | Load by id |
| `ScheduleService.extendInstanceWindow` | read-write | For each schedule: generate `[today, today+365]`, bulk insert with `ON CONFLICT DO NOTHING`; return total inserted. Idempotent, safe on several nodes |
| `FlightSearchService.search` | read-only | §4.2, §8 |
| `SeatMapService.getSeatMap` | read-only | Load instance + aircraft → taken seats → walk the layout |
| `BookingService.createBooking` | read-write | Validate request → **lock instance** → expire overdue holds → departed? → seats in layout? → taken seats (`SeatOccupancyQueries.takenSeats` ∩ requested)? → PNR → `policy.newBooking` → `saveAndFlush` → `instance.reserve(n)` |
| `BookingService.getBooking` | read-only | Load booking with seats by reference → load its instance → result with `effectiveStatus(now)` |
| `BookingService.confirmBooking` | read-write | Instance id by reference → **lock instance** → expire overdue holds → load booking with seats → CONFIRMED: return; CANCELLED: 409 `BOOKING_NOT_CONFIRMABLE`; EXPIRED: 409 `HOLD_EXPIRED`; departed: 409 `FLIGHT_NOT_BOOKABLE`; else `booking.confirm(now)` |
| `BookingService.cancelBooking` | read-write | Instance id by reference → **lock instance** → expire overdue holds → load booking with seats → CANCELLED/EXPIRED: return unchanged; `cancellationPolicy.verifyCancellable(...)` (409 `BOOKING_NOT_CANCELLABLE`); `n = booking.cancel(now)`, `instance.release(n)` |

**Lock order, the same on every write path:**
`lock flight_instance → expire overdue holds → read/load booking rows → mutate → update counter`.
One transaction locks exactly one instance, so lock waits cannot form a cycle.

**Why cancellation takes the flight lock even though seats are found by reference.** The
seats themselves cannot be released twice: setting `RELEASED` twice changes nothing. The
counter can: two unlocked cancels would both read `CONFIRMED`, and both would add `n` to
`available_seats`, advertising seats that do not exist. JPA also writes the counter back as an
absolute value, so an unlocked cancel and a concurrent booking would overwrite each other.
And taking the flight lock first on every path keeps one lock order, which rules out deadlocks
with hold expiry.

**Why the booking is looked up by id first.** `findFlightInstanceIdByReference` returns only the
id, so the `Booking` entity is first loaded *after* the lock. If it were loaded earlier, the
persistence context would hold a stale copy and two concurrent cancels could both see
`CONFIRMED`. For the same reason, `findByIdForUpdate` must be the first load of the instance in
the transaction.

**Unique-index violations are not caught inside the transaction.** PostgreSQL has already
aborted it, so the exception propagates and the handler maps it to 409.

**Rollback undoes reaping.** If a write path throws after expiring holds (e.g. 409
`SEAT_UNAVAILABLE`), the expiry is rolled back too. This is harmless: reads already treat those
holds as free, and the next successful write on that flight reaps them.

**Nothing slow inside a transaction:** no remote calls; PNR generation is in-memory with at
most 5 `existsByReference` checks.

## 8. Seat-hold read rules

The flag is read only in `BookingPolicyConfig`. Every rule below runs with the flag on or off;
with it off there are no `HELD` rows, so each one reduces to the plain behaviour.

- **Expiry (writes, under the lock):** `findOverdueHolds(instanceId, now)` = `HELD` bookings on
  the instance with `hold_expires_at <= now`; each `expireHold(now)`; the instance's counter is
  incremented by the seats released; then an explicit `flush()`, because the seat check that follows
  is plain SQL (`SeatOccupancyQueries.takenSeats`), which Hibernate's automatic flush does not cover.
- **Hold expiry value:** `now + ttl` rounded down to whole seconds (customer-facing; the database
  stores microseconds). `hold_expires_at` is set for bookings created `HELD` and kept after the hold
  ends as a record; the API shows `holdExpiresAt` only while the status is `HELD`.
- **Seat map:** a seat is `BOOKED` if its `booking_seat` row is `ACTIVE`, or `HELD` and its
  booking's `hold_expires_at > now`; otherwise `AVAILABLE`.
- **Search:** `availableSeats = available_seats + (seats on HELD bookings of that instance with
  hold_expires_at <= now)`. Two indexed queries: the instance search, then one grouped count
  (`SeatOccupancyQueries.seatsOnOverdueHolds`) for the instances found. The count reaches
  `booking_seat` with SQL because `flight` must not import `booking` code; with the flag off it
  returns nothing. It uses the `uq_active_seat` index, which covers `HELD` rows.
- **Fetch booking:** `status = booking.effectiveStatus(now)`.

## 9. Logging

SLF4J with Logback; the pattern includes `%X{requestId}`. INFO: schedule created (id, instance
count), window job (inserted count), booking created (reference, instance id, seat numbers),
confirmed, cancelled, holds expired (count). Passenger names are never logged at INFO.

Error logging is by status class, in `GlobalExceptionHandler`:
- **4xx**: WARN, one line with `code`, method, path and the detail; no stack trace. These are
  client or business outcomes (a seat conflict is an expected result under load), not faults.
- **5xx**: ERROR with the full stack trace. `503 LOCK_TIMEOUT` is logged at WARN without a stack
  trace, because it is a contention signal rather than a bug.

## 10. Testing approach

| Level | What | How |
| --- | --- | --- |
| Unit (test-first) | `SeatLayout`, `FlightInstanceGenerator` (weekdays, inclusive window ends, leap day, overnight), `BookingStatus` transitions, `Booking` hold expiry, `PnrGenerator` | Plain JUnit 5 + AssertJ, no Spring |
| Integration | Each endpoint's happy path and every reachable error code; cancel-then-rebook; window job re-run inserts 0 | `@SpringBootTest` + MockMvc on a Testcontainers `postgres:16` with the real Flyway migrations; fixed `Clock` at 2026-01-05T00:00Z; tables truncated in `@BeforeEach`; no `@Transactional` on tests |
| Concurrency | 50 threads / one seat → exactly 1 success; 50 threads / 50 seats; overlapping 1A+1B vs 1B+1C; 10 concurrent cancels; cancel vs book loop. After each: counter invariant and no duplicate live seat | `ExecutorService` + `CountDownLatch` start gate calling `BookingService` directly; shown to fail with the lock and index removed |
| Seat hold on | Hold → confirm; hold → expire (advance clock) → another customer books; confirm after expiry → 409; confirm-vs-expiry race | Separate test class with `airline.seat-hold.enabled=true` and a mutable test clock |
| Architecture | Controllers do not access repositories; `..api..` not used by service/domain/persistence; no package cycles | ArchUnit |
