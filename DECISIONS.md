# Decision log

One line per decision, newest phase last. The reasoning for the major ones is in
`hld/architecture.md` (section 9) and, from Phase 8, in `docs/adr/`.

## Design (Phase D)
- Flight instances are materialised one row per operating date for 365 days ahead; seats are derived, not stored.
- Concurrency: pessimistic lock on the flight-instance row for every write, plus the partial unique index `uq_active_seat` as a backstop; READ COMMITTED; 3 s lock timeout → 503.
- No `@Version` column: the flight lock already serialises cancellations; optimistic locking is a documented alternative.
- Availability is a counter on `flight_instance`, changed only under the flight lock.
- Seat hold is optional, behind `airline.seat-hold.enabled` (default `false`) and `airline.seat-hold.ttl`; expired holds are released lazily under the flight lock, with no scheduler.
- The seat-hold flag is read only where the `BookingPolicy` bean is chosen; hold expiry and hold-aware reads run either way and find nothing when the flag is off.
- `CancellationPolicy` is a Strategy with one implementation (cancellable before departure): a deliberate exception to "no interface without a second implementation".
- Lookup and cancel use the booking reference only; a surname check is a documented future step.
- Arrival-day offset is derived (arrival at or before departure = next day), stored as 0 or 1; flights over 24 h are out of scope.
- `SeatLayout` is the only value object; seat numbers and references stay `String`, days stay `Set<DayOfWeek>`.
- `FlightInstanceGenerator` and `InstanceWindowJob` live in `schedule`, and seat-occupancy reads live in `flight.persistence`, so feature packages have no cycles.
- Services return `*Result` records used directly as response bodies; requests map to `*Command` records.
- Errors: RFC 9457 ProblemDetail with `code` and `requestId` on every error, framework errors included; 400 for all invalid input (not 422), 409 for state conflicts; 4xx logged at WARN, 5xx at ERROR.
- New error codes beyond the original catalogue: `HOLD_EXPIRED`, `BOOKING_NOT_CONFIRMABLE`, `RESOURCE_NOT_FOUND`, `METHOD_NOT_ALLOWED`, `NOT_ACCEPTABLE`, `UNSUPPORTED_MEDIA_TYPE`.
- Every `201` carries a `Location` header.

## Phase 0 – Skeleton
- Project generated with Spring Initializr on Spring Boot 4.1.1 / Java 21; POM not hand-written.
- Tests live in `tests/java`, migrations in `db/migrations` (packaged as `classpath:db/migration`), to match the required repository layout.
- Surefire only (no Failsafe): every test runs in the `test` phase of `./mvnw verify`, with `-Duser.timezone=UTC`.
- springdoc-openapi 3.1.1 (the Boot 4 line) for Swagger UI at `/swagger-ui.html`.
- No Spring Boot Docker Compose support dependency: Compose is started explicitly (`docker compose up`), not by the application.
- Integration tests share `TestcontainersConfiguration`: a `postgres:16` container and a fixed clock at 2026-01-05T00:00:00Z (a Monday).
- The Docker image build skips tests because they need Docker; tests run with `./mvnw verify`.

## Phase 1 – Schema and reference data
- Migrations V1–V3 are `db/schema.sql` split by area (same DDL and comments); V4 holds seed data only. A `pg_dump --schema-only` of each showed identical schemas.
- Seed aircraft use fictional registrations `A6-XYA` (A320, 180 seats), `A6-XYB` (B777, 400), `A6-XYC` (ATR72, 72), with explicit ids 1–3 and the identity sequence moved past them.
- Read-only repositories extend Spring Data's bare `Repository` and declare only the finders in use, so seeded data has no save or delete methods.
- `SeatLayout` validates its own invariants (rows 1–99, distinct letters A–Z); duplicate letters are checked only in Java.
- `SeatLayout.contains` is case-sensitive and does not trim; normalising seat input is the booking validator's job, so it happens in one place.

## Phase 2 – Schedules and instance generation
- `ApiException` is one concrete class carrying an `ErrorCode` (which holds the HTTP status); subclasses only when a response carries extra fields. `ErrorCode` gains constants in the phase that first uses them.
- The handler skeleton adds `code` to every framework error by status (400 → `VALIDATION_ERROR`, 404 → `RESOURCE_NOT_FOUND`, 405/406/415) and an `errors` list for bean-validation failures; Phase 6 completes it.
- The instance window is [today, today + 365], 366 dates, both ends inclusive; today's instance is created even if it has already departed (search and booking filter departed flights).
- An arrival time equal to the departure time is treated as next-day (offset 1).
- Schedule times are returned as `HH:mm` (`@JsonFormat`), matching the request format; Jackson 3's default would add seconds.
- `FlightInstanceBulkWriter` inserts in JDBC batches of 500 with `ON CONFLICT DO NOTHING` and returns the rows actually inserted.
- `findByIdForUpdate` and `FlightInstance` behaviour (`reserve`, `release`, `isDepartedAt`) are deferred to the phases that use and test them.
- All integration tests extend one `IntegrationTest` base (one Spring context, one PostgreSQL container); each test starts from truncated transactional tables.

## Phase 3 – Search and seat map
- Search adds back seats on overdue holds with a second, grouped query for the instances found (not a correlated subquery): `flight` reaches `booking_seat` only through SQL, and this avoids a persistence-layer row type.
- Hold-aware reads (seat map BOOKED for live holds only; search count) are built and tested now with SQL fixtures; Phase 4b adds only the write side.
- Seat-map `availableSeats` is total seats minus the taken seats in the same response, so the number always matches the list.
- Departed flights still have a seat map (read by id); search hides them and booking rejects them.
- Query-parameter constraint failures (`HandlerMethodValidationException`) also return an `errors` list, with the parameter name as `field`.
- The seat-map availability enum is `SeatAvailability { AVAILABLE, BOOKED }`, named apart from booking's `SeatStatus`.

## Phase 4 – Booking
- **Race test shown failing, then passing.** 50 threads book seat 12A on one flight at once. With the flight lock replaced by a plain read *and* `uq_active_seat` commented out of V3: **10 successes out of 50, in each of 3 runs** (10 = the connection-pool size: every transaction that ran concurrently booked the seat). With `findByIdForUpdate` and the index restored (V3 byte-identical, `git diff` empty): **exactly 1 success, 49 `SeatUnavailableException`, in each of 3 runs.** The broken state was never committed.
- The concurrency class (same seat ×50, 50 different seats, overlapping 1A+1B / 1B+1C ×20) passed 5 runs in a row; after each scenario the counter equals total minus active seats and no seat is active twice.
- The seat-conflict check reuses `SeatOccupancyQueries.takenSeats`: one definition of a taken seat for seat map, search and booking.
- `BookingPolicy`, HELD/EXPIRED and confirm are deferred to Phase 4b; `BookingStatus`/`SeatStatus` gain constants in the phase that uses them.
- `ApiException.extraProperties()` lets subclasses (`SeatUnavailableException`, `InvalidSeatException`) add fields such as `unavailableSeats` to the error body.
- The maximum passengers per booking is checked by `BookingRequestValidator` from `airline.max-seats-per-booking`, not a hard-coded `@Size`.
- Booking references are matched exactly as given (PNRs are upper case).
- A seat conflict is logged at INFO with flight id and seat numbers; passenger names are never logged.
- The unique-index violation → 409 mapping is added in Phase 6; with the lock in place the index is not reached in these tests.

## Phase 5 – Cancellation
- Cancellation looks up only the flight id by reference (`findFlightInstanceIdByReference`, no entity), locks the flight, then loads the booking: same lock order as booking, and the booking is never a stale copy.
- `BookingStatus.canTransitionTo` is the enum state machine (CONFIRMED → CANCELLED; CANCELLED terminal). An illegal transition throws `IllegalStateException` (a bug → 500); the expected case, already cancelled, is returned unchanged by the service first (idempotent 200, nothing released or logged).
- `CancellationPolicy` has one implementation, `BeforeDepartureCancellationPolicy` (`@Component`, no config switch until a second rule exists); it is called under the lock after the idempotent check.
- `FlightInstance.release(n)` is guarded by `total_seats`, mirroring `reserve`.
- The cancel response is the full booking with status CANCELLED; its seats stay listed as a record of what was booked.
- Concurrency test helpers (start-gate runner, outcome counting, inventory invariant) live in one shared `ConcurrencySupport` used by both concurrency classes. Both classes passed 5 runs in a row.

## Phase 4b – Seat hold
- `BookingPolicy` (Strategy) with `ImmediateConfirmationPolicy` and `SeatHoldPolicy`, chosen once in `BookingPolicyConfig`: the only place `airline.seat-hold.enabled` is read. The policies are plain classes, so exactly one bean exists.
- Lazy expiry: every write on a flight (book, confirm, cancel) locks the flight, then releases its overdue holds, then works on bookings. No scheduler.
- `expireHolds` flushes before the seat check, because that check is plain SQL and Hibernate's automatic flush does not cover it.
- If a write fails after releasing holds (e.g. 409), the release is rolled back with it; harmless, since reads already treat those holds as free and the next successful write releases them.
- Confirm: CONFIRMED → 200 unchanged (also with the flag off); CANCELLED → 409 `BOOKING_NOT_CONFIRMABLE`; EXPIRED (or overdue) → 409 `HOLD_EXPIRED`; departed flight → 409 `FLIGHT_NOT_BOOKABLE`. Cancel of an EXPIRED booking → 200 unchanged.
- `hold_expires_at` is kept after a hold ends as a record; the API shows `holdExpiresAt` only while HELD. The expiry is rounded down to whole seconds (it is shown to customers; PostgreSQL keeps microseconds). V3's comment "set only while HELD" is left as written (applied migration); the design docs were reworded.
- Tests: `MutableClock` replaces the fixed test clock (reset before every test); flag-on tests run in their own Spring context. The confirm-vs-expiry race test uses the clock as its oracle: just before expiry the confirmation always wins, just after the other customer always wins, in whichever order the threads reach the lock (20 rounds). `SeatHoldTest` and both concurrency classes passed 5 runs in a row.
- Smoke-tested in Docker with the flag on (`AIRLINE_SEAT_HOLD_ENABLED=true` via `docker compose run`): HELD → confirm → CONFIRMED; and with the default (flag off): CONFIRMED, no `holdExpiresAt`.

## Phase 6 – Cross-cutting
- Every error, framework errors included, is a ProblemDetail with `status`, `title`, `detail`, `code` and `requestId`; `ErrorContractTest` checks all 21 reachable codes/variants (with the seat hold off; `HOLD_EXPIRED` in `SeatHoldTest`) and that bodies never contain exception, class, SQL or constraint names.
- Framework `detail`: Spring's text is kept when present and safe; ours replaces it when missing, for unknown paths ("No endpoint matches this path" instead of "No static resource"), and for malformed JSON (our text plus the field path from Jackson, e.g. `daysOfOperation[2]`).
- Unique-constraint violations are mapped by Hibernate's constraint name: `uq_active_seat` → 409 `SEAT_UNAVAILABLE`, `uq_schedule_flight_number` → 409 `DUPLICATE_FLIGHT_NUMBER`, anything else → 500. The name is logged, never returned. Unit-tested on the handler; over HTTP the duplicate-flight-number race (10 threads) always yields one 201 and nine 409s, never a 500.
- Lock timeout (`PessimisticLockingFailureException`) → 503 `LOCK_TIMEOUT` with `Retry-After: 1`; tested by holding the flight row lock in another transaction (the booking fails after the 3 s `lock_timeout`, then succeeds once the lock is free).
- Logging: 4xx and 503 at WARN on one line (method, path, code, detail); other 5xx at ERROR with stack trace. Every line carries `[requestId]` via `logging.pattern.level`; lines outside a request show `[]`.
- Incoming `X-Request-Id` is accepted only if it matches `^[A-Za-z0-9._-]{1,64}$`; otherwise a UUID is generated (prevents log injection).
- ArchUnit 1.4.1 (test scope), three rules: controllers do not access repositories (anything in `..persistence..` or any Spring Data `Repository`); `..api..` is not used by service, domain or persistence; no cycles between feature packages.

## Phase 7 – Hardening
- Edge cases covered through the API (`EdgeCasesTest`): the last day of the window (today + 365) is searchable and bookable, today + 366 is rejected; an overnight flight shows next-day arrival and is bookable; a schedule created late in the day generates today's (already departed) instance, which search hides and booking rejects; the last free seat can be booked, after which the flight shows 0 available and a further booking is a 409; a request including taken seats books nothing.
- A full flight stays in search results with `availableSeats: 0` (hiding it would be filtering beyond the brief).
- The end-to-end smoke test is committed as `scripts/smoke-test.sh`: bash and curl only, the date computed (next Monday at least a day ahead), PASS/FAIL per step, non-zero exit at the first failure; it needs a fresh database. Run with `bash scripts/smoke-test.sh` (git does not record the executable bit here, `core.filemode=false`).
- `./mvnw clean verify` passed three times in a row (298 tests, about 40 s each); the smoke test passed all 10 checks on a fresh Compose stack, and failed cleanly (exit 1 at step 1) when re-run on a used database.

## Phase 8 – Documentation
- README follows the brief's required sections (setup with dependencies/build/run/database initialization, design decisions, search, booking and cancellation algorithms, assumptions), plus API walkthrough, design principles and patterns, scaling and evolution (including a multiple-airlines summary), testing approach, repository layout and an AI-assistance note.
- Six decision records in `docs/adr/` (instances, concurrency, seat hold, package by feature, no authentication, PostgreSQL and engine-specific features).
- `db/schema.sql` stays the commented snapshot rather than raw `pg_dump` output (which drops the comments); it was re-verified identical to a migrated database (`pg_dump --schema-only`, 45 CREATE/ALTER statements).
- `hld/architecture.pdf` generated with md-to-pdf, pointed at the headless Chrome already installed for the diagrams (16 pages, the 5 diagrams embedded).
- Every relative link and anchor in README, HLD, LLD and ADRs resolves (44 checked); every README command was executed as written (Compose run, developer run, seat-hold run, smoke test, build).
