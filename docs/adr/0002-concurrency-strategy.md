# ADR 0002 – Concurrency: lock the flight row, back it with a partial unique index

**Status:** accepted

## Context
Two customers must never get the same seat on the same flight, even when they press "book" at the
same instant, and even with several application instances running. Multi-seat bookings are
all-or-nothing. The available-seat counter used by search must stay exact.

## Decision
Two layers:
1. **Primary: a pessimistic lock on the `flight_instance` row.** Every write (book, confirm,
   cancel) starts with `SELECT … FOR UPDATE` (`FlightInstanceRepository.findByIdForUpdate`, JPA
   `PESSIMISTIC_WRITE`). Writes on one flight run one at a time; other flights stay parallel. The
   seat check, the inserts and the counter update all happen under the lock.
2. **Backstop: the partial unique index** `uq_active_seat` on
   `booking_seat (flight_instance_id, seat_number) WHERE status IN ('ACTIVE','HELD')`. The
   database itself cannot store two live claims on one seat. A violation maps to 409
   `SEAT_UNAVAILABLE`, never a 500.

Supporting rules:
- **READ COMMITTED.** The seat query runs after the lock is granted, so it sees the previous
  holder's committed booking.
- **`lock_timeout = 3s`** per connection. A request that cannot get the lock fails fast with
  503 `LOCK_TIMEOUT` and `Retry-After: 1`.
- **One lock order everywhere:** lock the flight, then (seat hold) release expired holds, then
  read or change bookings. A transaction locks exactly one flight, so no deadlock cycle can form.
- **Cancellation reads only the flight id by reference, locks, and then loads the booking.**
  Two simultaneous cancellations therefore cannot both see CONFIRMED and both give the seats back
  to the counter.

## Alternatives
- **Optimistic locking (`@Version`):** on a popular flight, contention turns into failed
  requests and client retries; the row lock is held for milliseconds instead.
- **Unique index only, no lock:** prevents double booking, but cannot keep the counter exact
  without extra machinery, and gives vaguer errors for multi-seat requests.
- **Per-seat locks:** need a row per seat (rejected in ADR 0001), and multi-seat requests must
  lock in a fixed order.
- **JVM locks:** correct on one instance only.
- **SERIALIZABLE:** correct, but replaces waiting with serialisation-failure retries.

## Consequences
- **Proven by test:** with the lock replaced by a plain read *and* the index removed, 10 of 50
  simultaneous bookings of one seat succeeded (3 runs). With both in place, exactly 1 succeeds
  and 49 get `SEAT_UNAVAILABLE` (`BookingConcurrencyTest`, 5 runs in a row). Overlapping
  multi-seat requests (1A+1B vs 1B+1C) never book partially. Concurrent cancellations release
  once.
- Throughput on one flight is serialised. That's acceptable: the lock is held for a few
  milliseconds, and different flights never wait on each other.
- Correctness does not depend on the application tier, so it holds across any number of
  instances.

## When to revisit
- Measured lock waits on a single very popular flight become significant: consider per-seat
  claims through the unique index alone, with the counter derived.
- A move to a database without row locks or partial indexes (see ADR 0006 for the MySQL
  equivalent).
