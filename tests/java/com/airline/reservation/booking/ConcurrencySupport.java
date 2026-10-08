package com.airline.reservation.booking;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.springframework.jdbc.core.JdbcTemplate;

import com.airline.reservation.booking.service.SeatUnavailableException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs tasks on many threads released at the same instant, and checks the seat inventory
 * afterwards. Shared by the booking and cancellation concurrency tests.
 */
class ConcurrencySupport implements AutoCloseable {

	/** How a task ended. Any other exception fails the test: it is neither a success nor the expected conflict. */
	enum Outcome {
		SUCCESS, SEAT_UNAVAILABLE
	}

	@FunctionalInterface
	interface TaskFactory {
		Callable<?> create(int index);
	}

	private final JdbcTemplate jdbcTemplate;
	private final ExecutorService executor = Executors.newFixedThreadPool(50);

	ConcurrencySupport(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/** Starts every task from a shared start line (a latch) and records how each one ended. */
	List<Outcome> runConcurrently(int threads, TaskFactory tasks) throws Exception {
		CountDownLatch startLine = new CountDownLatch(1);
		List<Future<Outcome>> futures = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			Callable<?> task = tasks.create(i);
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
			outcomes.add(future.get(60, TimeUnit.SECONDS));
		}
		return outcomes;
	}

	static long count(List<Outcome> outcomes, Outcome outcome) {
		return outcomes.stream().filter(o -> o == outcome).count();
	}

	/** The counter matches the rows, and no seat is active twice. */
	void assertSeatInventoryConsistent(long flightId) {
		Integer totalSeats = jdbcTemplate.queryForObject("SELECT total_seats FROM flight_instance WHERE id = ?",
				Integer.class, flightId);
		assertThat(availableSeats(flightId)).isEqualTo(totalSeats - activeSeatCount(flightId));
		Integer duplicated = jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM (
				  SELECT seat_number FROM booking_seat
				  WHERE flight_instance_id = ? AND status = 'ACTIVE'
				  GROUP BY seat_number HAVING COUNT(*) > 1) d
				""", Integer.class, flightId);
		assertThat(duplicated).as("seats booked more than once").isZero();
	}

	int availableSeats(long flightId) {
		return jdbcTemplate.queryForObject("SELECT available_seats FROM flight_instance WHERE id = ?",
				Integer.class, flightId);
	}

	int activeSeatCount(long flightId) {
		return jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM booking_seat WHERE flight_instance_id = ? AND status = 'ACTIVE'",
				Integer.class, flightId);
	}

	@Override
	public void close() {
		executor.shutdownNow();
	}

}
