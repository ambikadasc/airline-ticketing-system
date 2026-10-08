package com.airline.reservation.hardening;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import com.airline.reservation.IntegrationTest;
import com.airline.reservation.schedule.service.CreateScheduleCommand;
import com.airline.reservation.schedule.service.ScheduleService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The booking window is configuration, not code: with {@code airline.booking-window-days=30}
 * generation, search and booking all follow the new value. Today (the test clock) is 2026-01-05.
 */
@TestPropertySource(properties = "airline.booking-window-days=30")
class BookingWindowConfigTest extends IntegrationTest {

	@Autowired
	private ScheduleService scheduleService;

	@Test
	void generationSearchAndBookingAllUseTheConfiguredWindow() throws Exception {
		scheduleService.createSchedule(new CreateScheduleCommand("XY101", "DXB", "LHR", LocalTime.parse("09:30"),
				LocalTime.parse("13:45"), 1L, EnumSet.allOf(DayOfWeek.class)));

		// Generation: today plus 30 days, both ends included.
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM flight_instance", Integer.class)).isEqualTo(31);

		// Search: day 30 is the last searchable date.
		search("2026-02-04").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
		search("2026-02-05")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("OUTSIDE_BOOKING_WINDOW"));
	}

	@Test
	void aFlightLeftBeyondAShortenedWindowCannotBeBooked() throws Exception {
		scheduleService.createSchedule(new CreateScheduleCommand("XY101", "DXB", "LHR", LocalTime.parse("09:30"),
				LocalTime.parse("13:45"), 1L, EnumSet.allOf(DayOfWeek.class)));
		// An instance on day 40, as if generated while the window was still longer.
		jdbcTemplate.update("""
				INSERT INTO flight_instance (schedule_id, flight_number, origin_code, destination_code, aircraft_id,
				  flight_date, departure_at, arrival_at, total_seats, available_seats)
				VALUES (1, 'XY101', 'DXB', 'LHR', 1, DATE '2026-02-14',
				  TIMESTAMPTZ '2026-02-14 09:30:00+00', TIMESTAMPTZ '2026-02-14 13:45:00+00', 180, 180)
				""");
		Long farFlight = jdbcTemplate.queryForObject(
				"SELECT id FROM flight_instance WHERE flight_date = DATE '2026-02-14'", Long.class);

		mockMvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON).content("""
						{"flightInstanceId": %d, "passengers": [{"name": "Ayesha Khan", "seatNumber": "12A"}]}
						""".formatted(farFlight)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("OUTSIDE_BOOKING_WINDOW"));
	}

	private ResultActions search(String date) throws Exception {
		return mockMvc.perform(get("/api/v1/flights")
				.param("origin", "DXB").param("destination", "LHR").param("date", date));
	}

}
