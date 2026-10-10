-- Load-test seed: schedules, a year of flight instances and a given share of seats already
-- booked, written directly in SQL (generate_series) so a million bookings take seconds, not an
-- hour of API calls. Only ever run on the fresh, throwaway load-test database
-- (scripts/load-test.sh); it assumes empty tables and explicit ids starting at 1.
--
-- Parameters (psql -v):  schedules  number of schedules, default 100
--                        fill       share of each flight's seats already booked, default 0.3
--
-- Rows produced are exactly what the application would have written: the same routes, the
-- same instance dates as the generator (strictly inside the booking window, so every instance
-- is bookable during the run), valid seat numbers for the A320, references in the
-- application's own alphabet, available_seats equal to total minus active seats.
\if :{?schedules}
\else
\set schedules 100
\endif
\if :{?fill}
\else
\set fill 0.3
\endif
\set ON_ERROR_STOP on
\timing on

-- 1. Schedules LT1..LTn, every day, on the A320 (aircraft 1). Routes cycle through every ordered
--    pair of the 10 seeded airports, so every route a search can ask for has flights.
WITH ap AS (
    SELECT code, row_number() OVER (ORDER BY code) - 1 AS r FROM airport
), routes AS (
    SELECT a.code AS origin, b.code AS destination,
           row_number() OVER (ORDER BY a.r, b.r) - 1 AS k
    FROM ap a JOIN ap b ON a.r <> b.r
)
INSERT INTO flight_schedule (id, flight_number, origin_code, destination_code,
                             departure_time, arrival_time, arrival_day_offset, aircraft_id)
SELECT n, 'LT' || n, r.origin, r.destination,
       make_time(6 + n % 14, (n * 7) % 60, 0),          -- departures 06:00 .. 19:59
       make_time(9 + n % 14, (n * 7) % 60, 0),          -- three hours later, never overnight
       0, 1
FROM generate_series(1, :schedules) AS n
JOIN routes r ON r.k = (n - 1) % (SELECT count(*) FROM routes);

INSERT INTO flight_schedule_day (schedule_id, day_of_week)
SELECT s.id, d
FROM flight_schedule s
CROSS JOIN unnest(ARRAY['MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY']) AS d;

SELECT setval(pg_get_serial_sequence('flight_schedule', 'id'), (SELECT max(id) FROM flight_schedule));

-- 2. One instance per schedule per day, tomorrow .. today + 365 (the bookable range; the daily
--    job tops up the look-ahead day itself, as it would in production).
INSERT INTO flight_instance (schedule_id, flight_number, origin_code, destination_code, aircraft_id,
                             flight_date, departure_at, arrival_at, total_seats, available_seats)
SELECT s.id, s.flight_number, s.origin_code, s.destination_code, s.aircraft_id,
       d::date,
       (d::date + s.departure_time) AT TIME ZONE 'UTC',
       (d::date + s.arrival_time)   AT TIME ZONE 'UTC',
       a.seats, a.seats
FROM flight_schedule s
CROSS JOIN generate_series(current_date + 1, current_date + 365, interval '1 day') AS d
CROSS JOIN (SELECT row_count * length(seat_letters) AS seats FROM aircraft WHERE id = 1) a
ORDER BY s.id, d;

-- 3. Bookings: the first fill x 180 seats of every flight, two seats per booking; every tenth
--    booking cancelled with its seats released (so the partial index has rows to skip).
CREATE TEMP TABLE seed_seat AS
SELECT i.id AS instance_id,
       (s / 6 + 1)::text || substr('ABCDEF', s % 6 + 1, 1) AS seat_number,
       dense_rank() OVER (ORDER BY i.id, s / 2) AS booking_id
FROM flight_instance i
CROSS JOIN generate_series(0, (:fill * 180)::int - 1) AS s;

-- Reference: the booking id in base 32 over the application's alphabet (no 0/O/1/I), six
-- characters, so every seeded reference is unique and valid.
INSERT INTO booking (id, reference, flight_instance_id, status, passenger_count, created_at, cancelled_at)
SELECT b.booking_id,
       (SELECT string_agg(substr('ABCDEFGHJKLMNPQRSTUVWXYZ23456789', ((b.booking_id >> (5 * p)) & 31)::int + 1, 1), '' ORDER BY p DESC)
        FROM generate_series(0, 5) AS p),
       b.instance_id,
       CASE WHEN b.booking_id % 10 = 0 THEN 'CANCELLED' ELSE 'CONFIRMED' END,
       b.seats,
       now() - (b.booking_id % 300) * interval '1 day',
       CASE WHEN b.booking_id % 10 = 0 THEN now() - (b.booking_id % 200) * interval '1 day' END
FROM (SELECT booking_id, min(instance_id) AS instance_id, count(*) AS seats
      FROM seed_seat GROUP BY booking_id) b;

INSERT INTO booking_seat (booking_id, flight_instance_id, seat_number, passenger_name, status, created_at, released_at)
SELECT ss.booking_id, ss.instance_id, ss.seat_number, 'Passenger ' || ss.seat_number,
       CASE WHEN ss.booking_id % 10 = 0 THEN 'RELEASED' ELSE 'ACTIVE' END,
       b.created_at, b.cancelled_at
FROM seed_seat ss JOIN booking b ON b.id = ss.booking_id;

SELECT setval(pg_get_serial_sequence('booking', 'id'), (SELECT max(id) FROM booking));

-- 4. The availability counter, as the application maintains it under the flight lock.
UPDATE flight_instance i
SET available_seats = total_seats - (SELECT count(*) FROM booking_seat bs
                                     WHERE bs.flight_instance_id = i.id AND bs.status IN ('ACTIVE', 'HELD'));

ANALYZE;
-- The bulk load above writes ~0.5 GB of WAL in a minute; flush it now rather than letting the
-- checkpoint fall into the measured window (observed once: an 8 s checkpoint during the run made
-- 181 requests wait more than 2 s for a connection and get 503 RETRY_LATER).
CHECKPOINT;

SELECT 'schedules' AS "table", count(*) FROM flight_schedule
UNION ALL SELECT 'flight instances', count(*) FROM flight_instance
UNION ALL SELECT 'bookings', count(*) FROM booking
UNION ALL SELECT 'booking seats', count(*) FROM booking_seat;
