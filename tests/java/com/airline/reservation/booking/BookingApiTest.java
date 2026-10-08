package com.airline.reservation.booking;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import com.airline.reservation.IntegrationTest;
import com.airline.reservation.schedule.service.CreateScheduleCommand;
import com.airline.reservation.schedule.service.ScheduleService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Booking through HTTP. Today (the fixed test clock) is 2026-01-05 00:00 UTC; flights are daily at 09:30. */
class BookingApiTest extends IntegrationTest {

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
	void booksSeatsAndReturnsEveryField() throws Exception {
		MvcResult result = book(passenger("Ayesha Khan", "12A"), passenger("Bilal Khan", "12B"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.bookingReference", matchesPattern("[A-Z2-9]{6}")))
				.andExpect(jsonPath("$.flightInstanceId").value(flightId))
				.andExpect(jsonPath("$.flightNumber").value("XY101"))
				.andExpect(jsonPath("$.flightDate").value("2026-01-06"))
				.andExpect(jsonPath("$.passengerCount").value(2))
				.andExpect(jsonPath("$.seats[0].seatNumber").value("12A"))
				.andExpect(jsonPath("$.seats[0].passengerName").value("Ayesha Khan"))
				.andExpect(jsonPath("$.seats[1].seatNumber").value("12B"))
				.andExpect(jsonPath("$.seats[1].passengerName").value("Bilal Khan"))
				.andExpect(jsonPath("$.status").value("CONFIRMED"))
				.andReturn();
		String reference = JsonPath.read(result.getResponse().getContentAsString(),
				"$.bookingReference");
		assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/v1/bookings/" + reference);

		mockMvc.perform(get("/api/v1/bookings/{reference}", reference))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.bookingReference").value(reference))
				.andExpect(jsonPath("$.seats[*].seatNumber", contains("12A", "12B")))
				.andExpect(jsonPath("$.status").value("CONFIRMED"));
	}

	@Test
	void bookedSeatsShowAsBookedAndLeaveTheCount() throws Exception {
		book(passenger("Ayesha Khan", "12A"), passenger("Bilal Khan", "12B")).andExpect(status().isCreated());

		mockMvc.perform(get("/api/v1/flights/{id}/seats", flightId))
				.andExpect(jsonPath("$.availableSeats").value(178))
				.andExpect(jsonPath("$.seats[?(@.seatNumber == '12A')].status", contains("BOOKED")))
				.andExpect(jsonPath("$.seats[?(@.seatNumber == '12B')].status", contains("BOOKED")))
				.andExpect(jsonPath("$.seats[?(@.seatNumber == '12C')].status", contains("AVAILABLE")));
		mockMvc.perform(get("/api/v1/flights").param("origin", "DXB").param("destination", "LHR")
						.param("date", "2026-01-06"))
				.andExpect(jsonPath("$[0].availableSeats").value(178));
	}

	@Test
	void aTakenSeatFailsTheWholeRequest() throws Exception {
		book(passenger("Ayesha Khan", "12A"), passenger("Bilal Khan", "12B")).andExpect(status().isCreated());

		book(passenger("Chen Wei", "12B"), passenger("Dana Ali", "12C"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SEAT_UNAVAILABLE"))
				.andExpect(jsonPath("$.unavailableSeats", contains("12B")));

		// All or nothing: 12C, which was free, was not booked either.
		assertThat(activeSeats()).isEqualTo(2);
		assertThat(availableSeats()).isEqualTo(178);
	}

	@Test
	void aSeatNotOnTheAircraftIsInvalid() throws Exception {
		book(passenger("Ayesha Khan", "31A"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_SEAT"))
				.andExpect(jsonPath("$.invalidSeats", contains("31A")));
	}

	@Test
	void seatNumbersAreNormalised() throws Exception {
		book(passenger("Ayesha Khan", " 12a "))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.seats[0].seatNumber").value("12A"));
	}

	@Test
	void theSameSeatTwiceInOneRequestIsRejected() throws Exception {
		book(passenger("Ayesha Khan", "12A"), passenger("Bilal Khan", "12a"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void moreThanNinePassengersIsRejected() throws Exception {
		String[] ten = new String[10];
		for (int i = 0; i < 10; i++) {
			ten[i] = passenger("Passenger " + i, "1" + "ABCDEFGHJK".charAt(i));
		}
		book(ten)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void aBlankPassengerNameIsRejected() throws Exception {
		book(passenger(" ", "12A"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[*].field", hasItem("passengers[0].name")));
	}

	@Test
	void anUnknownFlightIsNotFound() throws Exception {
		bookOn(999_999, passenger("Ayesha Khan", "12A"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("FLIGHT_NOT_FOUND"));
	}

	@Test
	void aDepartedFlightCannotBeBooked() throws Exception {
		jdbcTemplate.update("UPDATE flight_instance SET departure_at = TIMESTAMPTZ '2026-01-04 09:30:00+00', "
				+ "arrival_at = TIMESTAMPTZ '2026-01-04 13:45:00+00' WHERE id = ?", flightId);

		book(passenger("Ayesha Khan", "12A"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("FLIGHT_NOT_BOOKABLE"));
		assertThat(activeSeats()).isZero();
	}

	@Test
	void anUnknownReferenceIsNotFound() throws Exception {
		mockMvc.perform(get("/api/v1/bookings/ZZZZZZ"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("BOOKING_NOT_FOUND"));
	}

	@Test
	void createdBookingCarriesALocationHeader() throws Exception {
		book(passenger("Ayesha Khan", "12A"))
				.andExpect(header().string("Location", matchesPattern("/api/v1/bookings/[A-Z2-9]{6}")));
	}

	// ---------------------------------------------------------------------------------------

	private ResultActions book(String... passengers) throws Exception {
		return bookOn(flightId, passengers);
	}

	private ResultActions bookOn(long flight, String... passengers) throws Exception {
		String body = """
				{"flightInstanceId": %d, "passengers": [%s]}
				""".formatted(flight, String.join(",", passengers));
		return mockMvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private static String passenger(String name, String seat) {
		return """
				{"name": "%s", "seatNumber": "%s"}""".formatted(name, seat);
	}

	private int activeSeats() {
		return jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM booking_seat WHERE flight_instance_id = ? AND status = 'ACTIVE'",
				Integer.class, flightId);
	}

	private int availableSeats() {
		return jdbcTemplate.queryForObject("SELECT available_seats FROM flight_instance WHERE id = ?",
				Integer.class, flightId);
	}

}
