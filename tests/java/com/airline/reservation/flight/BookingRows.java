package com.airline.reservation.flight;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.jdbc.core.JdbcTemplate;

import com.airline.reservation.TestcontainersConfiguration;

/**
 * Inserts booking and booking_seat rows directly, to put seats into a given state for read-side
 * tests (search and seat map) without going through the booking use case.
 */
class BookingRows {

	private static final AtomicInteger NEXT_REFERENCE = new AtomicInteger();

	private final JdbcTemplate jdbcTemplate;

	BookingRows(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/** A booking with the given status whose seats all have {@code seatStatus}. */
	void insert(long flightInstanceId, String bookingStatus, Instant holdExpiresAt, String seatStatus,
			String... seats) {
		String reference = "T%05d".formatted(NEXT_REFERENCE.incrementAndGet());
		jdbcTemplate.update("""
				INSERT INTO booking (reference, flight_instance_id, status, passenger_count, created_at, hold_expires_at)
				VALUES (?, ?, ?, ?, ?, ?)
				""", reference, flightInstanceId, bookingStatus, seats.length, utc(TestcontainersConfiguration.TEST_NOW),
				holdExpiresAt == null ? null : utc(holdExpiresAt));
		Long bookingId = jdbcTemplate.queryForObject("SELECT id FROM booking WHERE reference = ?", Long.class,
				reference);
		for (String seat : seats) {
			jdbcTemplate.update("""
					INSERT INTO booking_seat (booking_id, flight_instance_id, seat_number, passenger_name, status, created_at)
					VALUES (?, ?, ?, 'Test Passenger', ?, ?)
					""", bookingId, flightInstanceId, seat, seatStatus, utc(TestcontainersConfiguration.TEST_NOW));
		}
	}

	private static Object utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

}
