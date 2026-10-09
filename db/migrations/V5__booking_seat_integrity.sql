-- V5: integrity rules the application already enforces, now also carried by the database.

-- booking_seat.flight_instance_id is copied from the booking (the partial unique index needs it on
-- this table). This composite foreign key makes the database refuse a seat row whose flight
-- differs from its booking's flight, so the copy can never drift.
ALTER TABLE booking
    -- Exists only as the target of the foreign key below (a referenced column set must be unique).
    ADD CONSTRAINT uq_booking_id_instance UNIQUE (id, flight_instance_id);

ALTER TABLE booking_seat
    ADD CONSTRAINT fk_booking_seat_booking_instance
        FOREIGN KEY (booking_id, flight_instance_id) REFERENCES booking (id, flight_instance_id);

-- Format rules, mirroring the API validation.
-- PostgreSQL regex; MySQL 8: CHECK (seat_number REGEXP '^[1-9][0-9]?[A-Z]$') etc.
ALTER TABLE booking_seat
    ADD CONSTRAINT chk_booking_seat_number CHECK (seat_number ~ '^[1-9][0-9]?[A-Z]$');

ALTER TABLE flight_schedule
    ADD CONSTRAINT chk_schedule_flight_number CHECK (flight_number ~ '^[A-Z0-9]{2}[0-9]{1,4}$');

ALTER TABLE flight_instance
    ADD CONSTRAINT chk_instance_flight_number CHECK (flight_number ~ '^[A-Z0-9]{2}[0-9]{1,4}$');
