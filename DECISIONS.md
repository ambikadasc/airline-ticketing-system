# Decision log

The decisions behind this system, one line each, grouped by topic. The reasoning for the major
ones, with the alternatives that were rejected, is in `hld/architecture.md` (section 9) and
`docs/adr/`.

## Flight generation
- Flight instances are materialised, one row per operating date, for today + `airline.booking-window-days` (default 365); seats are derived, not stored (ADR 0001).
- Instances are generated one day past the bookable window, so the newest bookable date already exists when the window moves at midnight, before the daily job runs; search and booking still stop at the window's end.
- Generation runs in the schedule-creation transaction, at startup, daily (cron, UTC) and on demand (`POST /admin/instance-window/extend`); all four use one idempotent JDBC batch insert (`ON CONFLICT (schedule_id, flight_date) DO NOTHING`).
- The startup run never stops the application: a failure is logged at WARN and the daily job or the endpoint catches up.
- Each job run logs under its own id (`job-startup-…`, `job-daily-…`) in the same logging key as an HTTP request id.
- The arrival-day offset is derived (arrival at or before departure means the next day), stored as 0 or 1; flights over 24 h are out of scope.
- `FlightInstanceGenerator` and the job live in `schedule`, so feature packages have no cycles.

## Concurrency and locking
- Every write (book, confirm, cancel) locks the `flight_instance` row first (`SELECT … FOR UPDATE`); the partial unique index `uq_active_seat` over ACTIVE and HELD seats is the database backstop (ADR 0002).
- READ COMMITTED; `lock_timeout = 3s` per connection, surfaced as 503 `LOCK_TIMEOUT` with `Retry-After`.
- One lock per transaction, always the flight: no deadlock cycle; cancellation looks up only the flight id by reference, locks, then loads the booking, so two concurrent cancels cannot both release the seats.
- No `@Version` column: the row lock already serialises writes.
- Availability is a counter on the flight, changed only under the lock; the invariant `available_seats = total − taken rows` is asserted in tests.

## Booking and references
- A booking is all or nothing; a conflict lists the taken seats in a 409.
- The seat-conflict check reuses `SeatOccupancyQueries.takenSeats`, the same definition of a taken seat as the seat map.
- Booking references are random (SecureRandom, 6 characters, no look-alike letters), not sequential, because the reference alone opens and cancels a booking; uniqueness comes from `uq_booking_reference`, and `existsByReference` (up to 5 draws) only avoids clashes with committed bookings (ADR 0005).
- A reference clash between two concurrent bookings, or no free reference in 5 draws, returns 503 `RETRY_LATER` with `Retry-After`; nothing is booked.
- The maximum passengers per booking is configuration (`airline.max-seats-per-booking`), checked by `BookingRequestValidator`, which has one private method per rule; a rule list is the refactor path if rules grow.
- `SeatLayout` is the only value object; seat numbers and references stay `String`, days stay `Set<DayOfWeek>`.
- Booking a flight dated beyond the window returns 400 `OUTSIDE_BOOKING_WINDOW`, so shortening the window takes effect at once.

## Cancellation
- Soft release: the booking becomes CANCELLED and its seats RELEASED; history is kept and the seats are bookable again at once.
- Idempotent: cancelling a CANCELLED (or EXPIRED) booking returns it unchanged.
- The one rule, "before departure", is an inline check in `BookingService`; a `CancellationPolicy` Strategy is introduced only when a second rule exists.
- Lookup and cancel use the reference only; a surname check is a documented future step.

## Seat hold (optional, off by default)
- `BookingPolicy` Strategy (`ImmediateConfirmationPolicy` or `SeatHoldPolicy`), chosen once from `airline.seat-hold.enabled` (ADR 0003).
- Expired holds are released lazily under the flight lock on every write; reads compute around overdue holds (seat map shows them AVAILABLE, search adds them back, GET reports EXPIRED). No scheduler.
- With the flag off the hold-specific queries (expiry on writes, overdue-hold count on search) are skipped, so the default path pays nothing for the feature; the flag is read only from `AirlineProperties`.
- Confirm: CONFIRMED returns unchanged (also with the flag off); CANCELLED → 409 `BOOKING_NOT_CONFIRMABLE`; EXPIRED or overdue → 409 `HOLD_EXPIRED`; departed → 409 `FLIGHT_NOT_BOOKABLE`.
- `hold_expires_at` is rounded to whole seconds, kept after the hold ends, and shown in the API only while HELD.

## Errors and logging
- Every error, framework errors included, is an RFC 9457 ProblemDetail with `code` and `requestId`; bodies never contain exception, class, SQL or constraint names.
- 400 for all invalid input (not 422), 404 for unknown ids, 409 for conflicts with resource state, 503 with `Retry-After` for temporary conditions where nothing changed.
- Unique-constraint violations are mapped by constraint name (`uq_active_seat` → 409, `uq_schedule_flight_number` → 409, `uq_booking_reference` → 503); anything else is a 500.
- 4xx and 503 are logged at WARN on one line; other 5xx at ERROR with the stack trace; passenger names are never logged.
- `X-Request-Id` is accepted only if it matches `^[A-Za-z0-9._-]{1,64}$`, otherwise a UUID is generated; the id is on every log line and in every error body.
- Distributed tracing is not built (one service, one database); it is a documented scaling step.
- Pool exhaustion (`CannotCreateTransactionException`: no connection obtained, nothing started) → 503 `RETRY_LATER`; a connection lost mid-transaction stays a 500 because the outcome is unknown and a "retry" promise could cause a double booking.
- Rate limiting and circuit breakers are not built (not asked for by the brief; the fail-fast timeouts bound the overload chain); they are documented scaling steps with triggers.

## Configuration
- All business settings are `airline.*` properties, overridable by environment variable (`AIRLINE_BOOKING_WINDOW_DAYS` etc.) and passed through by Compose when set; `airline.retry-after` serves both 503s.
- Format rules (seat, flight number, reference) stay in code and in database CHECKs because changing them needs a migration.
- PostgreSQL is published on `127.0.0.1:5433` by Compose so a local PostgreSQL on 5432 does not clash; the application's default URL matches.
- The pool is explicit (10 connections) and a request waits at most 2 s for one (not Hikari's 30 s default), so a busy flight cannot park every request thread behind the pool; with `lock_timeout = 3s` these are the three bounds on overload.

## Data model and database
- PostgreSQL 16, Flyway (forward-only V1–V5; seed data in V4), Hibernate validates only; tests run on the same engine (ADR 0006).
- `db/schema.sql` is a commented snapshot kept identical to the migrated schema (verified with `pg_dump --schema-only`); a raw dump is not used because it drops the comments.
- `booking_seat.flight_instance_id` is denormalised for the partial index; a composite foreign key to `booking (id, flight_instance_id)` keeps it equal to the booking's flight (V5).
- Seat-number and flight-number formats are CHECK constraints as well as API validation (V5).
- Every index is commented with the query it serves; PostgreSQL-specific SQL carries its MySQL equivalent.

## Code structure
- Package by feature, layered inside; dependencies run `booking → flight → aircraft, airport` and `schedule → flight, aircraft, airport`; enforced by three ArchUnit rules (ADR 0004).
- JPA entities are the domain model, with behaviour and an enum state machine; no public status setters.
- Services return `*Result` records used directly as response bodies; request records map to `*Command` records.
- `ApiException` is one concrete class carrying an `ErrorCode`; subclasses only where a response carries extra fields.
- Read-only reference data uses Spring Data's bare `Repository` with finders only.

## Testing and tooling
- Integration tests share one Spring context and one Testcontainers PostgreSQL, truncate tables before each test, use a resettable test clock, and never run inside a test transaction.
- The single-seat race test was shown failing with the lock and index removed (10 of 50 succeeded) before passing with them (exactly 1); recorded in ADR 0002.
- Flag-on tests run in their own context; the confirm-vs-expiry race uses the clock as its oracle.
- The smoke test (`scripts/smoke-test.sh`) needs only bash and curl, with portable `sed -E` and `date`.
- The image runs as an unprivileged user with a `HEALTHCHECK`; `chmod +x mvnw` in the Dockerfile keeps the build independent of the executable bit.
