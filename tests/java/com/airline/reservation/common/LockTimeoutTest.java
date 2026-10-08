package com.airline.reservation.common;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.airline.reservation.IntegrationTest;
import com.airline.reservation.schedule.service.CreateScheduleCommand;
import com.airline.reservation.schedule.service.ScheduleService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A booking that cannot get the flight lock within lock_timeout (3 s) fails fast with
 * 503 LOCK_TIMEOUT and Retry-After, instead of queueing indefinitely.
 */
class LockTimeoutTest extends IntegrationTest {

	@Autowired
	private ScheduleService scheduleService;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Test
	void aBookingThatCannotGetTheFlightLockIsToldToRetry() throws Exception {
		scheduleService.createSchedule(new CreateScheduleCommand("XY101", "DXB", "LHR", LocalTime.parse("09:30"),
				LocalTime.parse("13:45"), 1L, EnumSet.allOf(DayOfWeek.class)));
		long flightId = jdbcTemplate.queryForObject(
				"SELECT id FROM flight_instance WHERE flight_date = DATE '2026-01-06'", Long.class);

		// Another transaction holds the flight row lock, as a long-running write would.
		CountDownLatch lockHeld = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CompletableFuture<Void> holder = CompletableFuture.runAsync(() ->
				new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
					jdbcTemplate.queryForObject("SELECT id FROM flight_instance WHERE id = ? FOR UPDATE", Long.class,
							flightId);
					lockHeld.countDown();
					try {
						release.await(20, TimeUnit.SECONDS);
					}
					catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
					}
				}));
		lockHeld.await(10, TimeUnit.SECONDS);

		String booking = """
				{"flightInstanceId": %d, "passengers": [{"name": "Ayesha Khan", "seatNumber": "12A"}]}
				""".formatted(flightId);
		try {
			mockMvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON).content(booking))
					.andExpect(status().isServiceUnavailable())
					.andExpect(header().string("Retry-After", "1"))
					.andExpect(jsonPath("$.code").value("LOCK_TIMEOUT"))
					.andExpect(jsonPath("$.requestId").isNotEmpty());
		}
		finally {
			release.countDown();
			holder.get(20, TimeUnit.SECONDS);
		}

		// Once the lock is free, the same request succeeds.
		mockMvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON).content(booking))
				.andExpect(status().isCreated());
	}

}
