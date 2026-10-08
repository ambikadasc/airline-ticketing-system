# ADR 0001 – Materialise flight instances, derive seats

**Status:** accepted

## Context
Schedules are templates ("XY101, DXB→LHR, Mon/Wed/Fri 09:30"). Customers book a dated flight,
up to 365 days ahead. The brief allows three approaches: materialise instances, generate them
dynamically, or a hybrid. Bookings need something stable to reference, and concurrent bookings
need something to lock.

## Decision
Store one `flight_instance` row per operating date for [today, today + 365]. Seats are **not**
stored per instance: the seat map is the aircraft's `SeatLayout` minus the `booking_seat` rows
that are taken.
- Instances for a new schedule are generated in the same transaction that creates it
  (`FlightInstanceGenerator`, pure logic; `FlightInstanceBulkWriter`, one JDBC batch).
- `InstanceWindowJob` tops the window up daily and at startup with
  `INSERT … ON CONFLICT (schedule_id, flight_date) DO NOTHING`, so it is idempotent and safe after
  downtime or on several nodes.
- Route, flight number and aircraft are copied onto the instance (schedules never change), so
  search reads one table through one index.

## Alternatives
- **Fully dynamic:** compute dated flights from schedules at query time. There is no row to
  reference or lock, seat availability needs its own keyed storage anyway, and every search must
  evaluate every schedule.
- **Lazy hybrid:** create the instance on first booking. That puts a create-if-absent race on the
  hot booking path, and search still computes from schedules.
- **Materialise seats too:** 180–400 rows per instance, almost all never touched (up to about 40
  million rows a year at the target scale), for nothing the counter and `booking_seat` don't
  already give.

## Consequences
- Search is one indexed query (`idx_instance_search`), with no day-of-week logic at read time.
- Booking locks a concrete row (see ADR 0002).
- Volume is small: about 366 rows per daily schedule per year, about 100,000 a year at a few
  hundred schedules.
- The window must be kept filled. The job does this, and it is tested to insert 0 on a re-run
  and to fill gaps.
- Schedule edits would need instance regeneration; they are out of scope (schedules are
  immutable).

## When to revisit
- Schedules become editable (instances would need re-generating or versioning).
- The window or the number of schedules grows by orders of magnitude; then partition
  `flight_instance` by date.
