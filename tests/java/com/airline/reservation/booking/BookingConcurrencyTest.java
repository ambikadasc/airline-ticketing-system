package com.airline.reservation.booking;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.airline.reservation.IntegrationTest;
import com.airline.reservation.booking.domain.PassengerSeat;
import com.airline.reservation.booking.service.BookingService;
import com.airline.reservation.booking.service.CreateBookingCommand;
import com.airline.reservation.booking.service.SeatUnavailableException;
import com.airline.reservation.schedule.service.CreateScheduleCommand;
import com.airline.reservation.schedule.service.ScheduleService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Many threads book at the same instant (released together by a latch) against the real
 * database, calling the service directly. No test transaction: each call commits on its own,
 * exactly as concurrent HTTP requests would.
 */
class BookingConcurrencyTest extends IntegrationTest {

	@Autowired
	private BookingService bookingService;

	@Autowired
	private ScheduleService scheduleService;

	private ExecutorService executor;

	private long flightId;

	@BeforeEach
	void createFlight() {
		executor = Executors.newFixedThreadPool(50);
		scheduleService.createSchedule(new CreateScheduleCommand("XY101", "DXB", "LHR", LocalTime.parse("09:30"),
				LocalTime.parse("13:45"), 1L, EnumSet.allOf(DayOfWeek.class)));
		flightId = jdbcTemplate.queryForObject(
				"SELECT id FROM flight_instance WHERE flight_date = DATE '2026-01-06'", Long.class);
	}

	@AfterEach
	void stopThreads() {
		executor.shutdownNow();
	}

	@Test
	void fiftyThreadsBookingTheSameSeatProduceExactlyOneBooking() throws Exception {
		List<Outcome> outcomes = runConcurrently(50, i -> book("12A"));

		assertThat(successes(outcomes)).isEqualTo(1);
		assertThat(outcomes).filteredOn(Outcome::seatUnavailable).hasSize(49);
		assertSeatInventoryConsistent();
		assertThat(availableSeats()).isEqualTo(179);
	}

	@Test
	void fiftyThreadsBookingDifferentSeatsAllSucceed() throws Exception {
		List<String> seats = new ArrayList<>();
		for (int row = 1; row <= 9; row++) {
			for (char letter : "ABCDEF".toCharArray()) {
				seats.add(row + String.valueOf(letter));
			}
		}

		List<Outcome> outcomes = runConcurrently(50, i -> book(seats.get(i)));

		assertThat(successes(outcomes)).isEqualTo(50);
		assertSeatInventoryConsistent();
		assertThat(availableSeats()).isEqualTo(130);
	}

	@Test
	void overlappingMultiSeatRequestsNeverBookPartially() throws Exception {
		// Half want 1A+1B, half want 1B+1C: they all compete for 1B, so exactly one request can win.
		List<Outcome> outcomes = runConcurrently(20, i -> i % 2 == 0 ? book("1A", "1B") : book("1B", "1C"));

		assertThat(successes(outcomes)).isEqualTo(1);
		assertThat(outcomes).filteredOn(Outcome::seatUnavailable).hasSize(19);
		assertSeatInventoryConsistent();
		assertThat(activeSeatCount()).isEqualTo(2);
		assertThat(availableSeats()).isEqualTo(178);
	}

	// ---------------------------------------------------------------------------------------

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

	/** Runs the tasks from a shared start line and records how each one ended. */
	private List<Outcome> runConcurrently(int threads, TaskFactory tasks) throws Exception {
		CountDownLatch startLine = new CountDownLatch(1);
		List<Future<Outcome>> futures = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			Callable<Void> task = tasks.create(i);
			futures.add(executor.submit(() -> {
				startLine.await();
				try {
					task.call();
					return Outcome.SUCCESS;
				}
				catch (SeatUnavailableException ex) {
					return Outcome.SEAT_UNAVAILABLE;
				}
			}));
		}
		startLine.countDown();
		List<Outcome> outcomes = new ArrayList<>();
		for (Future<Outcome> future : futures) {
			// Any other exception fails the test here: it is neither a success nor the expected conflict.
			outcomes.add(future.get(60, TimeUnit.SECONDS));
		}
		return outcomes;
	}

	/** The counter matches the rows, and no seat is active twice. */
	private void assertSeatInventoryConsistent() {
		Integer totalSeats = jdbcTemplate.queryForObject("SELECT total_seats FROM flight_instance WHERE id = ?",
				Integer.class, flightId);
		assertThat(availableSeats()).isEqualTo(totalSeats - activeSeatCount());
		Integer duplicated = jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM (
				  SELECT seat_number FROM booking_seat
				  WHERE flight_instance_id = ? AND status = 'ACTIVE'
				  GROUP BY seat_number HAVING COUNT(*) > 1) d
				""", Integer.class, flightId);
		assertThat(duplicated).as("seats booked more than once").isZero();
	}

	private int availableSeats() {
		return jdbcTemplate.queryForObject("SELECT available_seats FROM flight_instance WHERE id = ?",
				Integer.class, flightId);
	}

	private int activeSeatCount() {
		return jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM booking_seat WHERE flight_instance_id = ? AND status = 'ACTIVE'",
				Integer.class, flightId);
	}

	private static long successes(List<Outcome> outcomes) {
		return outcomes.stream().filter(outcome -> outcome == Outcome.SUCCESS).count();
	}

	private enum Outcome {
		SUCCESS, SEAT_UNAVAILABLE;

		boolean seatUnavailable() {
			return this == SEAT_UNAVAILABLE;
		}
	}

	@FunctionalInterface
	private interface TaskFactory {
		Callable<Void> create(int index);
	}

}
