package com.airline.reservation.booking;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

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

/** Cancellation racing with itself and with booking, against the real database. */
class CancellationConcurrencyTest extends IntegrationTest {

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
	void tenSimultaneousCancellationsGiveTheSeatsBackOnce() throws Exception {
		String reference = book("12A", "12B");
		assertThat(concurrency.availableSeats(flightId)).isEqualTo(178);

		List<Outcome> outcomes = concurrency.runConcurrently(10, i -> () -> bookingService.cancelBooking(reference));

		assertThat(count(outcomes, Outcome.SUCCESS)).isEqualTo(10);
		assertThat(concurrency.availableSeats(flightId)).isEqualTo(180);
		concurrency.assertSeatInventoryConsistent(flightId);
	}

	@Test
	void cancellingAndRebookingTheSameSeatAtOnceStaysConsistent() throws Exception {
		for (int round = 0; round < 20; round++) {
			String reference = book("1A");

			// Thread 0 cancels the booking for 1A while thread 1 tries to book 1A. Whichever order the
			// lock gives them, the booking either wins (after the cancel) or gets SEAT_UNAVAILABLE.
			List<Outcome> outcomes = concurrency.runConcurrently(2,
					i -> i == 0 ? () -> bookingService.cancelBooking(reference) : () -> book("1A"));

			assertThat(outcomes.getFirst()).isEqualTo(Outcome.SUCCESS);
			concurrency.assertSeatInventoryConsistent(flightId);
			cancelEveryActiveBooking();
		}
	}

	private String book(String... seats) {
		List<PassengerSeat> passengers = Arrays.stream(seats)
				.map(seat -> new PassengerSeat("Passenger " + seat, seat))
				.toList();
		return bookingService.createBooking(new CreateBookingCommand(flightId, passengers)).bookingReference();
	}

	/** Resets the flight for the next round by cancelling whatever is still booked. */
	private void cancelEveryActiveBooking() {
		jdbcTemplate.queryForList("SELECT reference FROM booking WHERE status = 'CONFIRMED'", String.class)
				.forEach(bookingService::cancelBooking);
	}

}
