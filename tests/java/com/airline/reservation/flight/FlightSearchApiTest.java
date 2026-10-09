package com.airline.reservation.flight;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import com.airline.reservation.IntegrationTest;
import com.airline.reservation.schedule.service.CreateScheduleCommand;
import com.airline.reservation.schedule.service.ScheduleService;

import static java.time.DayOfWeek.FRIDAY;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.WEDNESDAY;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Flight search through HTTP. Today (the fixed test clock) is Monday 2026-01-05, 00:00 UTC. */
class FlightSearchApiTest extends IntegrationTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 1, 5);

	@Autowired
	private ScheduleService scheduleService;

	private BookingRows bookingRows;

	@BeforeEach
	void createMondayWednesdayFridaySchedule() {
		bookingRows = new BookingRows(jdbcTemplate);
		scheduleService.createSchedule(schedule("XY101", "09:30", "13:45", EnumSet.of(MONDAY, WEDNESDAY, FRIDAY)));
	}

	@Test
	void findsTheFlightOnAnOperatingDate() throws Exception {
		search("DXB", "LHR", TODAY)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].flightInstanceId").isNumber())
				.andExpect(jsonPath("$[0].flightNumber").value("XY101"))
				.andExpect(jsonPath("$[0].origin").value("DXB"))
				.andExpect(jsonPath("$[0].destination").value("LHR"))
				.andExpect(jsonPath("$[0].flightDate").value("2026-01-05"))
				.andExpect(jsonPath("$[0].departureTime").value("2026-01-05T09:30:00Z"))
				.andExpect(jsonPath("$[0].arrivalTime").value("2026-01-05T13:45:00Z"))
				.andExpect(jsonPath("$[0].availableSeats").value(180));
	}

	@Test
	void returnsAnEmptyListOnANonOperatingDate() throws Exception {
		search("DXB", "LHR", TODAY.plusDays(1)) // Tuesday
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	void acceptsBothEndsOfTheBookingWindow() throws Exception {
		search("DXB", "LHR", TODAY).andExpect(status().isOk());
		search("DXB", "LHR", TODAY.plusDays(365)).andExpect(status().isOk());
	}

	@Test
	void rejectsDatesOutsideTheBookingWindow() throws Exception {
		search("DXB", "LHR", TODAY.minusDays(1))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("OUTSIDE_BOOKING_WINDOW"));
		search("DXB", "LHR", TODAY.plusDays(366))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("OUTSIDE_BOOKING_WINDOW"));
	}

	@Test
	void hidesFlightsThatHaveAlreadyDeparted() throws Exception {
		// Departs at 00:00, which is exactly "now" today, so today's flight has gone.
		scheduleService.createSchedule(schedule("XY303", "00:00", "04:00", EnumSet.allOf(DayOfWeek.class)));

		search("DXB", "LHR", TODAY).andExpect(jsonPath("$[*].flightNumber", hasItem("XY101")))
				.andExpect(jsonPath("$", hasSize(1)));
		search("DXB", "LHR", TODAY.plusDays(1)).andExpect(jsonPath("$[0].flightNumber").value("XY303"));
	}

	@Test
	void withTheSeatHoldOffSearchReadsTheCounterAlone() throws Exception {
		// With the flag off no hold query runs (the hold-aware count is covered in SeatHoldTest).
		long flightId = flightIdOn(TODAY);
		jdbcTemplate.update("UPDATE flight_instance SET available_seats = 178 WHERE id = ?", flightId);
		bookingRows.insert(flightId, "HELD", Instant.parse("2026-01-04T23:50:00Z"), "HELD", "1A", "1B");

		search("DXB", "LHR", TODAY).andExpect(jsonPath("$[0].availableSeats").value(178));
	}

	@Test
	void sameOriginAndDestinationIsRejected() throws Exception {
		search("DXB", "DXB", TODAY)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void malformedAirportCodeIsRejected() throws Exception {
		search("dxb", "LHR", TODAY)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value("origin"));
	}

	@Test
	void missingParameterIsRejected() throws Exception {
		mockMvc.perform(get("/api/v1/flights").param("origin", "DXB").param("destination", "LHR"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void unparseableDateIsRejected() throws Exception {
		mockMvc.perform(get("/api/v1/flights").param("origin", "DXB").param("destination", "LHR")
						.param("date", "05-01-2026"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void unknownAirportIsNotFound() throws Exception {
		search("DXB", "XXX", TODAY)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("AIRPORT_NOT_FOUND"));
	}

	private ResultActions search(String origin, String destination, LocalDate date) throws Exception {
		return mockMvc.perform(get("/api/v1/flights")
				.param("origin", origin).param("destination", destination).param("date", date.toString()));
	}

	private long flightIdOn(LocalDate date) {
		return jdbcTemplate.queryForObject(
				"SELECT id FROM flight_instance WHERE flight_number = 'XY101' AND flight_date = ?", Long.class, date);
	}

	private static CreateScheduleCommand schedule(String flightNumber, String departure, String arrival,
			Set<DayOfWeek> days) {
		return new CreateScheduleCommand(flightNumber, "DXB", "LHR", LocalTime.parse(departure),
				LocalTime.parse(arrival), 1L, days);
	}

}
