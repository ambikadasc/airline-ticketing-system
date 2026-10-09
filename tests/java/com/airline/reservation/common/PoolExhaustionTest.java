package com.airline.reservation.common;

import java.sql.Connection;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumSet;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import com.airline.reservation.IntegrationTest;
import com.airline.reservation.schedule.service.CreateScheduleCommand;
import com.airline.reservation.schedule.service.ScheduleService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * When the connection pool is exhausted a request fails fast with a retryable 503 (nothing was
 * started), instead of parking for Hikari's 30 s default and taking a request thread with it.
 * Own context: a pool of one connection, half a second to obtain it.
 */
@TestPropertySource(properties = {
		"spring.datasource.hikari.maximum-pool-size=1",
		"spring.datasource.hikari.connection-timeout=500"})
class PoolExhaustionTest extends IntegrationTest {

	@Autowired
	private ScheduleService scheduleService;

	@Autowired
	private DataSource dataSource;

	@Test
	void aRequestThatCannotGetAConnectionIsToldToRetry() throws Exception {
		scheduleService.createSchedule(new CreateScheduleCommand("XY101", "DXB", "LHR", LocalTime.parse("09:30"),
				LocalTime.parse("13:45"), 1L, EnumSet.allOf(DayOfWeek.class)));
		long flightId = jdbcTemplate.queryForObject(
				"SELECT id FROM flight_instance WHERE flight_date = DATE '2026-01-06'", Long.class);
		String booking = """
				{"flightInstanceId": %d, "passengers": [{"name": "Ayesha Khan", "seatNumber": "12A"}]}
				""".formatted(flightId);

		// Hold the pool's only connection, as a storm of slow writes would.
		try (Connection held = dataSource.getConnection()) {
			long started = System.nanoTime();
			mockMvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON).content(booking))
					.andExpect(status().isServiceUnavailable())
					.andExpect(content().contentType("application/problem+json"))
					.andExpect(header().string("Retry-After", "1"))
					.andExpect(jsonPath("$.code").value("RETRY_LATER"))
					.andExpect(jsonPath("$.detail").value("The service is busy; nothing was changed. Please retry"))
					.andExpect(jsonPath("$.requestId").isNotEmpty());
			long waitedMillis = (System.nanoTime() - started) / 1_000_000;
			assertThat(waitedMillis).as("fails fast, not after 30 s").isLessThan(5_000);
		}

		// With the connection back in the pool the same request succeeds.
		mockMvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON).content(booking))
				.andExpect(status().isCreated());
	}

}
