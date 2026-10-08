package com.airline.reservation.schedule;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

import com.airline.reservation.IntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Ten admins create the same flight number at once. Whether a loser is caught by the service's check
 * or by the unique constraint, the answer is a 409, never a 500.
 */
class DuplicateFlightNumberRaceTest extends IntegrationTest {

	private static final String XY101 = """
			{"flightNumber": "XY101", "origin": "DXB", "destination": "LHR", "departureTime": "09:30",
			 "arrivalTime": "13:45", "aircraftId": 1, "daysOfOperation": ["MONDAY", "WEDNESDAY", "FRIDAY"]}
			""";

	@Test
	void exactlyOneScheduleIsCreatedAndEveryOtherRequestGetsAConflict() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(10);
		try {
			CountDownLatch startLine = new CountDownLatch(1);
			List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
			for (int i = 0; i < 10; i++) {
				futures.add(executor.submit(() -> {
					startLine.await();
					return mockMvc.perform(post("/api/v1/admin/schedules")
							.contentType(MediaType.APPLICATION_JSON).content(XY101)).andReturn().getResponse();
				}));
			}
			startLine.countDown();

			int created = 0;
			for (Future<MockHttpServletResponse> future : futures) {
				MockHttpServletResponse response = future.get(60, TimeUnit.SECONDS);
				if (response.getStatus() == 201) {
					created++;
				}
				else {
					assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(409);
					assertThat((String) JsonPath.read(response.getContentAsString(), "$.code"))
							.isEqualTo("DUPLICATE_FLIGHT_NUMBER");
				}
			}
			assertThat(created).isEqualTo(1);
			assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM flight_schedule", Integer.class)).isEqualTo(1);
		}
		finally {
			executor.shutdownNow();
		}
	}

}
