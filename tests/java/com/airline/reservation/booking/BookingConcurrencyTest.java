package com.airline.reservation.booking;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.airline.reservation.IntegrationTest;
import com.airline.reservation.booking.ConcurrencySupport.Outcome;
import com.airline.reservation.booking.domain.PassengerSeat;
import com.airline.reservation.booking.service.BookingService;
import com.airline.reservation.booking.service.CreateBookingCommand;
import com.airline.reservation.schedule.service.CreateScheduleCommand;
import com.airline.reservation.schedule.service.ScheduleService;

import static com.airline.reservation.booking.ConcurrencySupport.count;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Many threads book at the same instant against the real database, calling the service directly.
 * No test transaction: each call commits on its own, exactly as concurrent HTTP requests would.
 */
class BookingConcurrencyTest extends IntegrationTest {

	@Autowired
	private BookingService bookingService;

	@Autowired
	private ScheduleService scheduleService;

	private ConcurrencySupport concurrency;

	private long flightId;

	@BeforeEach
	void createFlight() {
		concurrency = new ConcurrencySupport(jdbcTemplate);
		scheduleService.createSchedule(new CreateScheduleCommand("XY101", "DXB", "LHR", LocalTime.parse("09:30"),
				LocalTime.parse("13:45"), 1L, EnumSet.allOf(DayOfWeek.class)));
		flightId = jdbcTemplate.queryForObject(
				"SELECT id FROM flight_instance WHERE flight_date = DATE '2026-01-06'", Long.class);
	}

	@AfterEach
	void stopThreads() {
		concurrency.close();
	}

	@Test
	void fiftyThreadsBookingTheSameSeatProduceExactlyOneBooking() throws Exception {
		List<Outcome> outcomes = concurrency.runConcurrently(50, i -> book("12A"));

		assertThat(count(outcomes, Outcome.SUCCESS)).isEqualTo(1);
		assertThat(count(outcomes, Outcome.SEAT_UNAVAILABLE)).isEqualTo(49);
		concurrency.assertSeatInventoryConsistent(flightId);
		assertThat(concurrency.availableSeats(flightId)).isEqualTo(179);
	}

	@Test
	void fiftyThreadsBookingDifferentSeatsAllSucceed() throws Exception {
		List<String> seats = new ArrayList<>();
		for (int row = 1; row <= 9; row++) {
			for (char letter : "ABCDEF".toCharArray()) {
				seats.add(row + String.valueOf(letter));
			}
		}

		List<Outcome> outcomes = concurrency.runConcurrently(50, i -> book(seats.get(i)));

		assertThat(count(outcomes, Outcome.SUCCESS)).isEqualTo(50);
		concurrency.assertSeatInventoryConsistent(flightId);
		assertThat(concurrency.availableSeats(flightId)).isEqualTo(130);
	}

	@Test
	void overlappingMultiSeatRequestsNeverBookPartially() throws Exception {
		// Half want 1A+1B, half want 1B+1C: they all compete for 1B, so exactly one request can win.
		List<Outcome> outcomes = concurrency.runConcurrently(20,
				i -> i % 2 == 0 ? book("1A", "1B") : book("1B", "1C"));

		assertThat(count(outcomes, Outcome.SUCCESS)).isEqualTo(1);
		assertThat(count(outcomes, Outcome.SEAT_UNAVAILABLE)).isEqualTo(19);
		concurrency.assertSeatInventoryConsistent(flightId);
		assertThat(concurrency.activeSeatCount(flightId)).isEqualTo(2);
		assertThat(concurrency.availableSeats(flightId)).isEqualTo(178);
	}

	private Callable<Void> book(String... seats) {
		return () -> {
			List<PassengerSeat> passengers = new ArrayList<>();
			for (String seat : seats) {
				passengers.add(new PassengerSeat("Passenger " + seat, seat));
			}
			bookingService.createBooking(new CreateBookingCommand(flightId, passengers));
			return null;
		};
	}

}
