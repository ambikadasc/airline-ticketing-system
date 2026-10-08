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
