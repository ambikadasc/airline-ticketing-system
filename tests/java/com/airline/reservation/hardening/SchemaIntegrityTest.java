package com.airline.reservation.hardening;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.airline.reservation.IntegrationTest;
import com.airline.reservation.schedule.service.CreateScheduleCommand;
import com.airline.reservation.schedule.service.ScheduleService;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Rules the database carries on its own (V5), independent of the application: a seat row cannot
 * name a different flight from its booking, and seat and flight numbers must be well-formed.
 */
class SchemaIntegrityTest extends IntegrationTest {

	@Autowired
	private ScheduleService scheduleService;

	private long flightA;
	private long flightB;

	@BeforeEach
	void twoFlights() {
		scheduleService.createSchedule(new CreateScheduleCommand("XY101", "DXB", "LHR", LocalTime.parse("09:30"),
				LocalTime.parse("13:45"), 1L, EnumSet.allOf(DayOfWeek.class)));
		flightA = flightOn("2026-01-06");
		flightB = flightOn("2026-01-07");
		jdbcTemplate.update("""
				INSERT INTO booking (reference, flight_instance_id, status, passenger_count, created_at)
				VALUES ('SCHEMA', ?, 'CONFIRMED', 1, now())
				""", flightA);
	}

	@Test
	void aSeatCannotBelongToADifferentFlightThanItsBooking() {
		assertThatThrownBy(() -> insertSeat(flightB, "12A"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("fk_booking_seat_booking_instance");
	}

	@Test
	void aSeatOnTheBookingsOwnFlightIsAccepted() {
		insertSeat(flightA, "12A");
	}

	@Test
	void aMalformedSeatNumberIsRejected() {
		assertThatThrownBy(() -> insertSeat(flightA, "A12"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("chk_booking_seat_number");
	}

	@Test
	void aMalformedFlightNumberIsRejected() {
		assertThatThrownBy(() -> jdbcTemplate.update("""
				INSERT INTO flight_schedule (flight_number, origin_code, destination_code, departure_time, arrival_time,
				                             aircraft_id, created_at)
				VALUES ('XY-101', 'DXB', 'LHR', TIME '09:30', TIME '13:45', 1, now())
				"""))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("chk_schedule_flight_number");
	}

	private void insertSeat(long flight, String seat) {
		jdbcTemplate.update("""
				INSERT INTO booking_seat (booking_id, flight_instance_id, seat_number, passenger_name, status, created_at)
				SELECT id, ?, ?, 'Test Passenger', 'ACTIVE', now() FROM booking WHERE reference = 'SCHEMA'
				""", flight, seat);
	}

	private long flightOn(String date) {
		return jdbcTemplate.queryForObject("SELECT id FROM flight_instance WHERE flight_date = CAST(? AS DATE)",
				Long.class, date);
	}

}
