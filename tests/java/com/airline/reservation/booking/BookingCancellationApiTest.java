package com.airline.reservation.booking;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumSet;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.airline.reservation.IntegrationTest;
import com.airline.reservation.schedule.service.CreateScheduleCommand;
import com.airline.reservation.schedule.service.ScheduleService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Cancellation through HTTP. Today (the fixed test clock) is 2026-01-05 00:00 UTC; the flight is 2026-01-06 09:30. */
class BookingCancellationApiTest extends IntegrationTest {

	@Autowired
	private ScheduleService scheduleService;

	private long flightId;

	@BeforeEach
	void createFlight() {
		scheduleService.createSchedule(new CreateScheduleCommand("XY101", "DXB", "LHR", LocalTime.parse("09:30"),
				LocalTime.parse("13:45"), 1L, EnumSet.allOf(DayOfWeek.class)));
		flightId = jdbcTemplate.queryForObject(
				"SELECT id FROM flight_instance WHERE flight_date = DATE '2026-01-06'", Long.class);
	}

	@Test
	void cancellingReleasesTheSeatsForOtherCustomers() throws Exception {
		String reference = book("12A", "12B");
		assertThat(availableSeats()).isEqualTo(178);

		cancel(reference)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.bookingReference").value(reference))
				.andExpect(jsonPath("$.status").value("CANCELLED"))
				.andExpect(jsonPath("$.seats[*].seatNumber", contains("12A", "12B")));

		mockMvc.perform(get("/api/v1/bookings/{reference}", reference))
				.andExpect(jsonPath("$.status").value("CANCELLED"));
		mockMvc.perform(get("/api/v1/flights/{id}/seats", flightId))
				.andExpect(jsonPath("$.availableSeats").value(180))
				.andExpect(jsonPath("$.seats[?(@.seatNumber == '12A')].status", contains("AVAILABLE")))
				.andExpect(jsonPath("$.seats[?(@.seatNumber == '12B')].status", contains("AVAILABLE")));
		assertThat(availableSeats()).isEqualTo(180);

		// The released seats can be booked again straight away.
		book("12B", "12C");
		assertThat(availableSeats()).isEqualTo(178);
	}

	@Test
	void cancellingTwiceReturnsTheSameResultAndReleasesOnce() throws Exception {
		String reference = book("12A", "12B");

		cancel(reference).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
		cancel(reference).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

		assertThat(availableSeats()).isEqualTo(180);
	}

	@Test
	void anUnknownReferenceIsNotFound() throws Exception {
		cancel("ZZZZZZ")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("BOOKING_NOT_FOUND"));
	}

	@Test
	void aBookingOnADepartedFlightCannotBeCancelled() throws Exception {
		String reference = book("12A");
		jdbcTemplate.update("UPDATE flight_instance SET departure_at = TIMESTAMPTZ '2026-01-04 09:30:00+00', "
				+ "arrival_at = TIMESTAMPTZ '2026-01-04 13:45:00+00' WHERE id = ?", flightId);

		cancel(reference)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("BOOKING_NOT_CANCELLABLE"));

		mockMvc.perform(get("/api/v1/bookings/{reference}", reference))
				.andExpect(jsonPath("$.status").value("CONFIRMED"));
		assertThat(availableSeats()).isEqualTo(179);
	}

	private String book(String... seats) throws Exception {
		StringBuilder passengers = new StringBuilder();
		for (String seat : seats) {
			if (!passengers.isEmpty()) {
				passengers.append(',');
			}
			passengers.append("{\"name\": \"Passenger %s\", \"seatNumber\": \"%s\"}".formatted(seat, seat));
		}
		String body = "{\"flightInstanceId\": %d, \"passengers\": [%s]}".formatted(flightId, passengers);
		String response = mockMvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(response, "$.bookingReference");
	}

	private ResultActions cancel(String reference) throws Exception {
		return mockMvc.perform(post("/api/v1/bookings/{reference}/cancel", reference));
	}

	private int availableSeats() {
		return jdbcTemplate.queryForObject("SELECT available_seats FROM flight_instance WHERE id = ?",
				Integer.class, flightId);
	}

}
