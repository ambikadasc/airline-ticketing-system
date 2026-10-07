-- V4: seed reference data. Airports and aircraft are preloaded and read-only (no CRUD API).

INSERT INTO airport (code, name, city, country) VALUES
    ('DXB', 'Dubai International Airport',            'Dubai',     'United Arab Emirates'),
    ('LHR', 'Heathrow Airport',                       'London',    'United Kingdom'),
    ('JFK', 'John F. Kennedy International Airport',  'New York',  'United States'),
    ('SIN', 'Singapore Changi Airport',               'Singapore', 'Singapore'),
    ('KHI', 'Jinnah International Airport',           'Karachi',   'Pakistan'),
    ('LHE', 'Allama Iqbal International Airport',     'Lahore',    'Pakistan'),
    ('ISB', 'Islamabad International Airport',        'Islamabad', 'Pakistan'),
    ('DOH', 'Hamad International Airport',            'Doha',      'Qatar'),
    ('FRA', 'Frankfurt Airport',                      'Frankfurt', 'Germany'),
    ('CDG', 'Paris Charles de Gaulle Airport',        'Paris',     'France');

-- Explicit ids keep the API examples stable (aircraftId 1 is the A320).
-- Codes are fictional registrations. Seats per aircraft = row_count x length(seat_letters).
INSERT INTO aircraft (id, code, aircraft_type, row_count, seat_letters) VALUES
    (1, 'A6-XYA', 'A320',  30, 'ABCDEF'),      -- 180 seats
    (2, 'A6-XYB', 'B777',  40, 'ABCDEFGHJK'),  -- 400 seats; no row letter I
    (3, 'A6-XYC', 'ATR72', 18, 'ABCD');        --  72 seats

-- Explicit ids do not advance the identity sequence; move it past the seeded rows.
-- PostgreSQL-specific. MySQL equivalent: ALTER TABLE aircraft AUTO_INCREMENT = 4;
SELECT setval(pg_get_serial_sequence('aircraft', 'id'), (SELECT MAX(id) FROM aircraft));
