package com.airline.reservation.flight;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.EnumSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import com.airline.reservation.IntegrationTest;
import com.airline.reservation.schedule.service.CreateScheduleCommand;
import com.airline.reservation.schedule.service.ScheduleService;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Seat map through HTTP. Now (the fixed test clock) is 2026-01-05T00:00:00Z. */
class SeatMapApiTest extends IntegrationTest {

	private static final Instant LIVE_HOLD_EXPIRY = Instant.parse("2026-01-05T00:10:00Z");
	private static final Instant EXPIRED_HOLD_EXPIRY = Instant.parse("2026-01-04T23:50:00Z");

	@Autowired
	private ScheduleService scheduleService;

	private BookingRows bookingRows;

	private long flightId;

	@BeforeEach
	void createFlight() {
		bookingRows = new BookingRows(jdbcTemplate);
		scheduleService.createSchedule(new CreateScheduleCommand("XY101", "DXB", "LHR", LocalTime.parse("09:30"),
				LocalTime.parse("13:45"), 1L, EnumSet.allOf(DayOfWeek.class)));
		flightId = jdbcTemplate.queryForObject(
				"SELECT id FROM flight_instance WHERE flight_date = DATE '2026-01-05'", Long.class);
	}

	@Test
	void freshFlightHasEverySeatAvailableInLayoutOrder() throws Exception {
		seatMap(flightId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.flightInstanceId").value(flightId))
				.andExpect(jsonPath("$.flightNumber").value("XY101"))
				.andExpect(jsonPath("$.flightDate").value("2026-01-05"))
				.andExpect(jsonPath("$.aircraftType").value("A320"))
				.andExpect(jsonPath("$.totalSeats").value(180))
				.andExpect(jsonPath("$.availableSeats").value(180))
				.andExpect(jsonPath("$.seats", hasSize(180)))
				.andExpect(jsonPath("$.seats[0].seatNumber").value("1A"))
				.andExpect(jsonPath("$.seats[1].seatNumber").value("1B"))
				.andExpect(jsonPath("$.seats[6].seatNumber").value("2A"))
				.andExpect(jsonPath("$.seats[179].seatNumber").value("30F"))
				.andExpect(jsonPath("$.seats[*].status", everyItem(is("AVAILABLE"))));
	}

	@Test
	void confirmedSeatsAreBookedAndReleasedSeatsAreAvailable() throws Exception {
		bookingRows.insert(flightId, "CONFIRMED", null, "ACTIVE", "1A", "1B");
		bookingRows.insert(flightId, "CANCELLED", null, "RELEASED", "1C");

		seatMap(flightId)
				.andExpect(jsonPath("$.availableSeats").value(178))
				.andExpect(jsonPath("$.seats[0].status").value("BOOKED"))
				.andExpect(jsonPath("$.seats[1].status").value("BOOKED"))
				.andExpect(jsonPath("$.seats[2].status").value("AVAILABLE"));
	}

	@Test
	void aLiveHoldShowsAsBookedAndAnExpiredOneAsAvailable() throws Exception {
		bookingRows.insert(flightId, "HELD", LIVE_HOLD_EXPIRY, "HELD", "1A");
		bookingRows.insert(flightId, "HELD", EXPIRED_HOLD_EXPIRY, "HELD", "1B");

		seatMap(flightId)
				.andExpect(jsonPath("$.availableSeats").value(179))
				.andExpect(jsonPath("$.seats[0].status").value("BOOKED"))
				.andExpect(jsonPath("$.seats[1].status").value("AVAILABLE"));
	}

	@Test
	void unknownFlightIsNotFound() throws Exception {
		seatMap(999_999)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("FLIGHT_NOT_FOUND"));
	}

	@Test
	void nonNumericFlightIdIsRejected() throws Exception {
		mockMvc.perform(get("/api/v1/flights/abc/seats"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	private ResultActions seatMap(long id) throws Exception {
		return mockMvc.perform(get("/api/v1/flights/{id}/seats", id));
	}

}
