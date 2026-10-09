package com.airline.reservation.booking;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.airline.reservation.IntegrationTest;
import com.airline.reservation.booking.domain.PassengerSeat;
import com.airline.reservation.booking.service.BookingService;
import com.airline.reservation.booking.service.CreateBookingCommand;
import com.airline.reservation.schedule.service.CreateScheduleCommand;
import com.airline.reservation.schedule.service.ScheduleService;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guessing booking references must stay slow: after 10 unsuccessful lookups in a minute a client is
 * refused on lookup, confirm and cancel until the minute ends. Everything else is unaffected.
 * Default settings (10 misses per PT1M); the limiter is reset before every test.
 */
class LookupThrottleTest extends IntegrationTest {

	private static final String OTHER_CLIENT = "198.51.100.9";

	@Autowired
	private ScheduleService scheduleService;

	@Autowired
	private BookingService bookingService;

	@Test
	void theEleventhMissInAMinuteIsRefusedUntilTheMinuteEnds() throws Exception {
		missTenTimes();

		lookup("ZZZZZZ")
				.andExpect(status().isTooManyRequests())
				.andExpect(content().contentType("application/problem+json"))
				.andExpect(jsonPath("$.code").value("RATE_LIMITED"))
				.andExpect(jsonPath("$.requestId").isNotEmpty())
				.andExpect(header().string("Retry-After", "60"));

		clock.advance(Duration.ofSeconds(61));
		lookup("ZZZZZZ").andExpect(status().isNotFound());
	}

	@Test
	void retryAfterCountsDownThroughTheWindow() throws Exception {
		missTenTimes();
		clock.advance(Duration.ofSeconds(40));

		lookup("ZZZZZZ")
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string("Retry-After", "20"));
	}

	@Test
	void confirmAndCancelAreRefusedToo() throws Exception {
		missTenTimes();

		mockMvc.perform(post("/api/v1/bookings/ZZZZZZ/confirm"))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMITED"));
		mockMvc.perform(post("/api/v1/bookings/ZZZZZZ/cancel"))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMITED"));
	}

	@Test
	void searchAndBookingAreNeverThrottled() throws Exception {
		long flightId = createFlight();
		missTenTimes();

		mockMvc.perform(get("/api/v1/flights").param("origin", "DXB").param("destination", "LHR")
						.param("date", "2026-01-06"))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON).content("""
						{"flightInstanceId": %d, "passengers": [{"name": "Ayesha Khan", "seatNumber": "12A"}]}
						""".formatted(flightId)))
				.andExpect(status().isCreated());
	}

	@Test
	void anotherClientIsNotAffected() throws Exception {
		missTenTimes();

		mockMvc.perform(get("/api/v1/bookings/ZZZZZZ").with(request -> {
			request.setRemoteAddr(OTHER_CLIENT);
			return request;
		})).andExpect(status().isNotFound());
	}

	@Test
	void pathVariantsCountAsMissesToo() throws Exception {
		for (int i = 0; i < 10; i++) {
			mockMvc.perform(get("/api/v1/bookings/ZZZZZZ;x=" + i)).andExpect(status().isNotFound());
		}

		lookup("ZZZZZZ").andExpect(status().isTooManyRequests());
	}

	@Test
	void successfulLookupsNeverCount() throws Exception {
		long flightId = createFlight();
		String reference = bookingService.createBooking(new CreateBookingCommand(flightId,
				List.of(new PassengerSeat("Ayesha Khan", "12A")))).bookingReference();

		for (int i = 0; i < 15; i++) {
			lookup(reference).andExpect(status().isOk());
		}
		lookup("ZZZZZZ").andExpect(status().isNotFound());
	}

	@Test
	void retryAfterIsAlwaysAtLeastOneSecond() throws Exception {
		missTenTimes();
		clock.advance(Duration.ofMillis(59_500));

		lookup("ZZZZZZ")
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string("Retry-After",
						allOf(greaterThanOrEqualTo("1"), lessThanOrEqualTo("1"))));
	}

	private void missTenTimes() throws Exception {
		for (int i = 0; i < 10; i++) {
			lookup("ZZZZZZ").andExpect(status().isNotFound());
		}
	}

	private ResultActions lookup(String reference) throws Exception {
		MockHttpServletRequestBuilder request = get("/api/v1/bookings/{reference}", reference);
		return mockMvc.perform(request);
	}

	private long createFlight() {
		scheduleService.createSchedule(new CreateScheduleCommand("XY101", "DXB", "LHR", LocalTime.parse("09:30"),
				LocalTime.parse("13:45"), 1L, EnumSet.allOf(DayOfWeek.class)));
		return jdbcTemplate.queryForObject("SELECT id FROM flight_instance WHERE flight_date = DATE '2026-01-06'",
				Long.class);
	}

}
