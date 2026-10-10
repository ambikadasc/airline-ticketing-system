# Airline Reservation System – Low-Level Design

Companion to [`hld/architecture.md`](../hld/architecture.md). The schema is in
[`db/schema.sql`](../db/schema.sql) and the ER diagram in
[`hld/diagrams/er.png`](../hld/diagrams/er.png). How to run it: [README](../README.md);
decision records: [`docs/adr`](../docs/adr/).

## 1. Modules and package layout

Base package `com.airline.reservation`, packaged by feature; inside a feature, by layer.

```
com.airline.reservation
├── AirlineReservationApplication      main(): TimeZone UTC, @EnableScheduling
├── common/
│   ├── config/       ClockConfig, AirlineProperties
│   ├── error/        ApiException (code, detail, optional extra body fields), ErrorCode, GlobalExceptionHandler
│   └── logging/      RequestIdFilter
├── airport/          Airport, AirportRepository
├── aircraft/         Aircraft, SeatLayout, AircraftRepository
├── schedule/
│   ├── api/          AdminScheduleController, AdminInstanceWindowController, CreateScheduleRequest
│   ├── service/      ScheduleService, FlightInstanceGenerator, CreateScheduleCommand, ScheduleResult,
│   │                 InstanceWindowResult
│   ├── domain/       FlightSchedule
│   ├── persistence/  FlightScheduleRepository
│   └── job/          InstanceWindowJob
├── flight/
│   ├── api/          FlightController
│   ├── service/      FlightSearchService, SeatMapService, FlightSearchResult, SeatMapResult
│   ├── domain/       FlightInstance
│   └── persistence/  FlightInstanceRepository, FlightInstanceBulkWriter, SeatOccupancyQueries
└── booking/
    ├── api/          BookingController, CreateBookingRequest,
    │                 LookupMissLimiter, BookingLookupThrottle, BookingWebConfig
    ├── service/      BookingService, BookingRequestValidator, PnrGenerator,
    │                 CreateBookingCommand, BookingResult
    ├── service/policy/  BookingPolicy, ImmediateConfirmationPolicy, SeatHoldPolicy,
    │                    BookingPolicyConfig
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
| `ClockConfig` | `@Bean Clock clock()` → `Clock.systemUTC()`. Tests replace it with a `@Primary` `MutableClock` that stands still at 2026-01-05T00:00Z until a test moves it |
| `AirlineProperties` | `@ConfigurationProperties("airline")` record: `bookingWindowDays` (365), `maxSeatsPerBooking` (9), `instanceJobCron`, `retryAfter` (PT1S), `seatHold { enabled=false, ttl=PT10M }`. `lastBookableDate(today)` is the single definition of the window, used by generation, search and booking. Every value is overridable by environment variable (README "Configuration") |
| `ApiException` | Concrete `RuntimeException(ErrorCode, detail)`. The `ErrorCode` already carries the HTTP status, so one class covers every not-found, conflict and validation case: `new ApiException(ErrorCode.AIRPORT_NOT_FOUND, "Airport not found: XXX")` |
| Extra fields | `ApiException` takes an optional map of extra properties for the error body (`unavailableSeats`, `invalidSeats`); no subclasses |
| `ErrorCode` | Enum of every code with its HTTP status (§6) |
| `GlobalExceptionHandler` | `@RestControllerAdvice` extending `ResponseEntityExceptionHandler`; the single place errors become responses (§6) and are logged by status (§9) |
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
| `ScheduleService` | `createSchedule(command)`, `getSchedule(id)`, `extendInstanceWindow()` → `InstanceWindowResult(inserted, windowEnd)` (§7) |
| `InstanceWindowJob` | `@Scheduled(cron = "${airline.instance-job-cron}", zone = "UTC")` and `ApplicationRunner`; both call `ScheduleService.extendInstanceWindow()`. Each run logs under its own id in the MDC (`job-startup-xxxxxxxx`, `job-daily-xxxxxxxx`), removed in `finally`. A failing startup run is logged at WARN and never stops the application (the daily job or the admin endpoint catches up) |
| `AdminScheduleController` | `POST /api/v1/admin/schedules`, `GET /api/v1/admin/schedules/{id}` |
| `AdminInstanceWindowController` | `POST /api/v1/admin/instance-window/extend`: the same top-up as the job, on demand; returns `InstanceWindowResult` |

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
| `BookingPolicy` | Interface, the Strategy: `Booking newBooking(String reference, Long flightInstanceId, List<PassengerSeat> passengers, Instant now)` |
| `ImmediateConfirmationPolicy` | Returns `Booking.confirmed(...)` |
| `SeatHoldPolicy` | Returns `Booking.held(..., now.plus(ttl))`, the expiry rounded down to whole seconds |
| `BookingPolicyConfig` | `@Bean BookingPolicy` chosen by `airline.seat-hold.enabled`. The flag is read only from `AirlineProperties`: here, and where hold-specific queries would otherwise run (`BookingService.expireHolds`, `FlightSearchService.search`), which skip them when it is off |
| `BookingRequestValidator` | One public `validate(command)`; one private method per rule (§5), including the optional `Idempotency-Key` (1–64 of `[A-Za-z0-9_-]`). Returns the normalised seat list |
| `BookingService.fingerprint` | Package-private static: hex SHA-256 of the normalised request, every field delimited. Stored on the booking next to its `Idempotency-Key`; a repeat with the same key but another fingerprint is refused |
| `PnrGenerator` | 6 characters from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (no 0/O/1/I) via `SecureRandom`. A plain class, no interface: there is no second implementation |
| `BookingService` | `createBooking`, `getBooking`, `confirmBooking`, `cancelBooking` (§7). `newReference()` draws up to 5 codes against `existsByReference`; if all are taken it throws `RETRY_LATER` (503) |
| `BookingController` | `POST /api/v1/bookings`, `GET /api/v1/bookings/{reference}`, `POST .../{reference}/confirm`, `POST .../{reference}/cancel` |
| `LookupMissLimiter` | Per-client count of `BOOKING_NOT_FOUND` answers in a fixed window (`airline.lookup-throttle`, default 10 per minute); `check(client)` throws `ApiException(RATE_LIMITED, …, retryAfter = time left)` once the count is reached; `recordMiss(client)`; `reset()` for tests. Memory-bounded (expired windows purged past 10,000 clients). Per instance; keyed by `remoteAddr` |
| `BookingLookupThrottle` | `HandlerInterceptor`: `preHandle` → `check`; `afterCompletion` → `recordMiss` when the response is 404. Registered by `BookingWebConfig` for `/api/v1/bookings/*`, `…/*/confirm`, `…/*/cancel`, i.e. exactly the routes Spring maps to the reference endpoints, so no path variant reaches them unchecked; creating a booking and search are not matched |

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
| POST | `/admin/instance-window/extend` | Generate missing flight instances up to the end of the window | 200 |

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
  "generatedInstances": 158
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
Optional request header `Idempotency-Key: 6f1c2a9e-order-1001` (1–64 of letters, digits, `-`,
`_`). A repeat with the same key and the same request returns the booking the first attempt
created: the same `201`, `Location` and body, with the booking's current status (so a cancelled
booking replays as `CANCELLED`). The same key with a different request (flight, seats, names or
their order) → 422 `IDEMPOTENCY_KEY_REUSED`. The key is looked up under the flight lock (§7);
the decision and its alternatives are in ADR 0007.

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

### 4.5 Top up the instance window
```http
POST /api/v1/admin/instance-window/extend
```
```json
{ "inserted": 10, "windowEnd": "2027-01-05" }
```
The same idempotent work as the startup and daily job; safe to call repeatedly or while the job runs.

### 4.6 Confirm, cancel, fetch
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
| Services and domain, under the lock | Flight exists; not departed; inside the booking window; every seat in the aircraft's `SeatLayout`; seats not taken; booking state allows the transition | 404 / 409 / 400 `OUTSIDE_BOOKING_WINDOW` / 400 `INVALID_SEAT` |
| Search and schedule services | Search date within `[today, today + 365]`; airports and aircraft exist; flight number not used | 400 `OUTSIDE_BOOKING_WINDOW`, 404, 409 |
| Database constraints | Unique flight number, unique (schedule, date), unique live seat, unique reference, counter bounds, status values, route, airport/seat-letter/seat-number/flight-number formats, and the composite FK that keeps `booking_seat.flight_instance_id` equal to its booking's flight (V5) | Last line of defence; the unique ones are mapped to 409 or 503 |

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
| 400 | `OUTSIDE_BOOKING_WINDOW` | Search date, or the date of a flight being booked, outside [today, today + `airline.booking-window-days`] |
| 404 | `AIRPORT_NOT_FOUND`, `AIRCRAFT_NOT_FOUND`, `SCHEDULE_NOT_FOUND`, `FLIGHT_NOT_FOUND`, `BOOKING_NOT_FOUND` | Unknown identifier |
| 404 | `RESOURCE_NOT_FOUND` | No endpoint at that path |
| 405 | `METHOD_NOT_ALLOWED` | Endpoint exists, HTTP method does not (response carries `Allow`) |
| 406 | `NOT_ACCEPTABLE` | `Accept` header excludes JSON |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Request body is not `application/json` |
| 409 | `SEAT_UNAVAILABLE` | A requested seat is taken (lists `unavailableSeats`) |
| 409 | `DUPLICATE_FLIGHT_NUMBER` | A schedule with that flight number exists |
| 409 | `FLIGHT_NOT_BOOKABLE` | Booking or confirming on a departed flight |
| 409 | `BOOKING_NOT_CANCELLABLE` | Cancelling a booking on a flight that has departed |
| 409 | `HOLD_EXPIRED` | Confirming a hold that has expired (status `EXPIRED`, or `HELD` past `hold_expires_at`) |
| 409 | `BOOKING_NOT_CONFIRMABLE` | Confirming a `CANCELLED` booking |
| 422 | `IDEMPOTENCY_KEY_REUSED` | The `Idempotency-Key` was already used for a booking request with a different fingerprint (also when the same key arrives at the same instant for two different flights: the loser's insert fails the partial unique index) |
| 429 | `RATE_LIMITED` | More than `airline.lookup-throttle.max-misses` unsuccessful lookup/confirm/cancel attempts from one client within the window; `Retry-After` = seconds left in the window |
| 503 | `LOCK_TIMEOUT` | Flight lock not acquired within 3 s; header `Retry-After` (`airline.retry-after`, default 1 s) |
| 503 | `RETRY_LATER` | No pooled connection within `connection-timeout` (2 s), two bookings drew the same reference at the same instant (unique constraint), or no free reference in 5 draws; in every case nothing was changed; header `Retry-After` |
| 500 | `INTERNAL_ERROR` | Anything unexpected; logged at ERROR with the stack trace, generic message to the client |

`GlobalExceptionHandler` mapping:
- Framework errors (unreadable body, bean/method validation, missing or mistyped parameters,
  unknown path, wrong method, media-type problems) are handled by the inherited
  `ResponseEntityExceptionHandler` methods. One override, `handleExceptionInternal`, then adds
  `code` (chosen from the status: 400 → `VALIDATION_ERROR`, 404 → `RESOURCE_NOT_FOUND`, 405, 406,
  415 as in the table) and `requestId` to all of them, and `errors: [{field, message}]` to
  validation failures.
- `ApiException` → its `ErrorCode` status and code, plus its extra properties, and a
  `Retry-After` header when the exception carries one (`retryAfter()`, used by the lookup throttle).
- `DataIntegrityViolationException` by constraint name: `uq_active_seat` → 409
  `SEAT_UNAVAILABLE`; `uq_schedule_flight_number` → 409 `DUPLICATE_FLIGHT_NUMBER`;
  `uq_booking_idempotency_key` → 422 `IDEMPOTENCY_KEY_REUSED`;
  `uq_booking_reference` → 503 `RETRY_LATER` with `Retry-After`; anything else
  → 500.
- `PessimisticLockingFailureException` (includes lock timeouts) → 503 `LOCK_TIMEOUT` with
  `Retry-After` from `airline.retry-after` (default 1 s).
- `CannotCreateTransactionException` (no connection obtained within the pool's
  `connection-timeout`; nothing started) → 503 `RETRY_LATER` with `Retry-After`.
  `DataAccessResourceFailureException` (a connection lost later, possibly mid-commit) is **not**
  mapped: the outcome is unknown, so it stays a 500 rather than promising a safe retry.
- Any other `Exception` → 500 `INTERNAL_ERROR` with the generic detail "An unexpected error occurred".
- `detail` is never empty: Spring's text is kept when present and safe; ours replaces it when
  missing, for unknown paths ("No endpoint matches this path") and for malformed JSON
  ("Invalid value for field 'daysOfOperation[2]'", built from Jackson's field path).

## 7. Transaction boundaries and lock order

`@Transactional` appears only on service methods. Isolation is the PostgreSQL default, READ
COMMITTED. `spring.jpa.open-in-view=false`, so all loading happens inside the service method.

| Method | Tx | Steps |
| --- | --- | --- |
| `ScheduleService.createSchedule` | read-write | Check airports, aircraft, flight number → `saveAndFlush` schedule (the JDBC insert needs its id) → generate `[today, lastBookableDate(today) + 1 day]` → bulk insert → return with count |
| `ScheduleService.getSchedule` | read-only | Load by id |
| `ScheduleService.extendInstanceWindow` | read-write | For each schedule: generate `[today, lastBookableDate(today) + 1 day]`, bulk insert with `ON CONFLICT DO NOTHING`; return `(inserted, windowEnd)`. Triggered at startup, daily, and by `POST /admin/instance-window/extend`. Idempotent, safe on several nodes and overlapping triggers |
| `FlightSearchService.search` | read-only | §4.2, §8 |
| `SeatMapService.getSeatMap` | read-only | Load instance + aircraft → taken seats → walk the layout |
| `BookingService.createBooking` | read-write | Validate request → **lock instance** → expire overdue holds → `Idempotency-Key` sent? look it up: same fingerprint → return that booking, different → 422 → departed? → inside the booking window? → seats in layout? → taken seats (`SeatOccupancyQueries.takenSeats` ∩ requested)? → PNR → `policy.newBooking` (+ key and fingerprint) → `saveAndFlush` → `instance.reserve(n)` |
| `BookingService.getBooking` | read-only | Load booking with seats by reference → load its instance → result with `effectiveStatus(now)` |
| `BookingService.confirmBooking` | read-write | Instance id by reference → **lock instance** → expire overdue holds → load booking with seats → CONFIRMED: return; CANCELLED: 409 `BOOKING_NOT_CONFIRMABLE`; EXPIRED: 409 `HOLD_EXPIRED`; departed: 409 `FLIGHT_NOT_BOOKABLE`; else `booking.confirm(now)` |
| `BookingService.cancelBooking` | read-write | Instance id by reference → **lock instance** → expire overdue holds → load booking with seats → CANCELLED/EXPIRED: return unchanged; flight departed? (409 `BOOKING_NOT_CANCELLABLE`); `n = booking.cancel(now)`, `instance.release(n)` |

**Lock order, the same on every write path:**
`lock flight_instance → expire overdue holds → read/load booking rows → mutate → update counter`.
One transaction locks exactly one instance, so lock waits cannot form a cycle.

**Why the idempotency key is looked up after the lock.** Two retries of one request carry the
same flight id, so they queue on the same lock; the second runs after the first has committed
and finds its booking (READ COMMITTED sees committed rows). Looked up before the lock, both
would miss and the second would book the seats again or hit the seat conflict, which is the
failure the key exists to prevent. The lookup costs one indexed query, only when the header is
present.

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

**Booking references are random, not sequential**, because the reference alone opens and cancels a
booking. Uniqueness is guaranteed by `uq_booking_reference`; the check only avoids committed
clashes. Two bookings on different flights (no shared lock) drawing the same code at the same
instant: the second insert violates the constraint, the transaction rolls back, and the client gets
503 `RETRY_LATER` (§6).

## 8. Seat-hold read rules

The flag is read only from `AirlineProperties`. With it off, no `HELD` row can exist, so the two
rules that need an extra query (expiry on writes, the overdue-hold count on search) are skipped;
the seat-map and fetch rules need no branch and run as written.

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
- **5xx**: ERROR with the full stack trace. The 503s (`LOCK_TIMEOUT`, `RETRY_LATER`) are logged at
  WARN without a stack trace: they are temporary conditions, not bugs.

Background job runs have no HTTP request, so `InstanceWindowJob` puts its own run id in the MDC
(`job-startup-…`, `job-daily-…`); every log line therefore carries an id.

## 10. Testing approach

353 tests, all run by `./mvnw verify`. Integration tests extend one `IntegrationTest` base: one
Spring context and one PostgreSQL 16 container (Testcontainers, real Flyway migrations), tables
truncated before each test, a `MutableClock` reset to 2026-01-05T00:00Z, and no `@Transactional`
on tests (they must see committed data).

| Level | Classes | What |
| --- | --- | --- |
| Unit (test-first) | `SeatLayoutTest`, `FlightInstanceGeneratorTest`, `FlightScheduleTest`, `FlightInstanceTest`, `BookingTest`, `BookingHoldTest`, `BookingStatusTest`, `BookingRequestValidatorTest`, `BookingFingerprintTest`, `PnrGeneratorTest`, `BookingPolicyTest`, `GlobalExceptionHandlerTest`, `InstanceWindowJobLogIdTest` | Pure logic without Spring: seat labels and validity; weekdays, inclusive window ends, leap day, overnight; the full status-transition table; hold expiry; validation rules; constraint-name mapping |
| API (MockMvc) | `ScheduleApiTest`, `FlightSearchApiTest`, `SeatMapApiTest`, `BookingApiTest`, `BookingCancellationApiTest`, `BookingIdempotencyTest`, `InstanceWindowJobTest`, `InstanceWindowApiTest`, `ReferenceDataTest`, `EdgeCasesTest`, `BookingWindowConfigTest` (own context, 30-day window), `SchemaIntegrityTest` (V5, V6 rules) | Every endpoint and response field; all-or-nothing booking; cancel then rebook; window job idempotent and gap-filling; window ends, overnight, late-day creation, last free seat; `Idempotency-Key`: same request replayed (same reference, `Location`, body; one booking), reused key → 422, no key unchanged, a replay after cancellation shows `CANCELLED`, 20 concurrent retries → one booking, the same key on two flights at once → one booking + one 422; held bookings replay as `HELD` then `CONFIRMED` (`SeatHoldTest`) |
| Error contract | `ErrorContractTest`, `LockTimeoutTest`, `PoolExhaustionTest` (own context: a one-connection pool, held by the test), `BookingReferenceClashTest` (own context; fixed generator + missed check, as in the race), `LookupThrottleTest`, `LookupMissLimiterTest` (unit, test clock), `RequestIdFilterTest` | All 23 reachable error codes share one shape and leak nothing; lock timeout and pool exhaustion → 503 with `Retry-After`; reference clash → 503 `RETRY_LATER`, nothing stored; the 11th unsuccessful lookup → 429 with `Retry-After` = time left, other clients, search and booking unaffected, path variants counted; request ids |
| Concurrency | `BookingConcurrencyTest`, `CancellationConcurrencyTest`, `DuplicateFlightNumberRaceTest` (helpers in `ConcurrencySupport`) | 50 threads on one seat → exactly 1 success; 50 seats; overlapping 1A+1B vs 1B+1C; 10 concurrent cancels; cancel vs book; duplicate flight number never 500. After each: counter invariant and no seat taken twice. Shown to fail with the lock and index removed (10 of 50 succeeded) |
| Seat hold on | `SeatHoldTest` (own context, `airline.seat-hold.enabled=true`) | Hold → confirm; expiry frees seats for the next customer; confirm after expiry → 409; confirm-vs-expiry race decided by the clock |
| Architecture | `ArchitectureTest` (ArchUnit) | Controllers do not access repositories; `..api..` not used by service/domain/persistence; no cycles between feature packages |
| Load (a tool, not a test) | `scripts/load-test.sh`, `load/seed.sql`, `load/airline-load.jmx` | Seeded JMeter run on a separate throwaway stack: search, lookup, cancel, spread and hot-flight bookings, a same-seat burst (exactly one 201), reference guessing (10 × 404 then 429); throughput and p50/p90/p95/p99 per scenario, responses counted by status and error code, inventory invariant after the run (README "Load test" and "Measured") |

## 11. Query plans at scale

Captured with `EXPLAIN (ANALYZE, BUFFERS)` on the load-test database (`scripts/load-test.sh`
defaults: 36,500 flight instances, 985,500 bookings, 1,971,000 seat rows; `booking` 177 MB,
`booking_seat` 330 MB). Each hot query is an index lookup, so its cost does not grow with the
tables:

| Query | Plan | Rows | Execution |
| --- | --- | --- | --- |
| Search by route and date (`FlightInstanceRepository.search`) | Index Scan using `idx_instance_search` (`origin_code`, `destination_code`, `flight_date`), filter `departure_at > now()`, sort by `departure_at` | 1 | 0.07 ms, 6 buffers |
| Lookup by reference (`BookingRepository.findWithSeatsByReference`, the `booking` side) | Index Scan using `uq_booking_reference` | 1 | 0.03 ms, 4 buffers |
| Taken seats on one flight (`SeatOccupancyQueries`: seat map, availability check) | Bitmap Index Scan on `uq_active_seat` (`flight_instance_id = ?`; the partial index holds only ACTIVE/HELD rows), then one index lookup per seat on `booking` via `uq_booking_id_instance` for the hold-expiry condition | 180 (a full flight) | 0.44 ms, 746 buffers |

The seat query is the most expensive: on a flight with every seat taken it touches one `booking`
row per seat for the hold-expiry test, and still runs in under half a millisecond. Had the
partial index not matched (`status IN ('ACTIVE','HELD')` must appear literally in the query), the
plan would be a scan of `idx_booking_seat_booking` or of the table; the predicate is kept in one
place (`SeatOccupancyQueries`) for that reason.
