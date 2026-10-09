package com.airline.reservation.hardening;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.airline.reservation.IntegrationTest;
import com.airline.reservation.aircraft.AircraftRepository;
import com.airline.reservation.schedule.service.CreateScheduleCommand;
import com.airline.reservation.schedule.service.ScheduleService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Boundary cases through the API. Today (the test clock) is Monday 2026-01-05, 00:00 UTC. */
class EdgeCasesTest extends IntegrationTest {

	private static final long A320 = 1L;
	private static final long ATR72 = 3L; // 18 rows x ABCD = 72 seats

	@Autowired
	private ScheduleService scheduleService;

	@Autowired
	private AircraftRepository aircraftRepository;

	@Test
	void theLastDayOfTheBookingWindowCanBeSearchedAndBooked() throws Exception {
		createDaily("XY101", "09:30", "13:45", A320);

		search("2027-01-05") // today + 365
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].availableSeats").value(180));
		book(flightId("XY101", "2027-01-05"), "12A").andExpect(status().isCreated());

		search("2027-01-06") // today + 366
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("OUTSIDE_BOOKING_WINDOW"));

		// Instances are generated one day past the window (for the daily job's lag after midnight);
		// that day exists but is not bookable until the window reaches it.
		book(flightId("XY101", "2027-01-06"), "12A")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("OUTSIDE_BOOKING_WINDOW"));
	}

	@Test
	void anOvernightFlightArrivesTheNextDayAndCanBeBooked() throws Exception {
		createDaily("XY202", "22:00", "02:15", A320);

		search("2026-01-06")
				.andExpect(jsonPath("$[0].departureTime").value("2026-01-06T22:00:00Z"))
				.andExpect(jsonPath("$[0].arrivalTime").value("2026-01-07T02:15:00Z"));
		book(flightId("XY202", "2026-01-06"), "1A").andExpect(status().isCreated());
	}

	@Test
	void aScheduleCreatedLateInTheDayCannotSellTodaysDepartedFlight() throws Exception {
		clock.set(Instant.parse("2026-01-05T23:00:00Z"));
		createDaily("XY101", "09:30", "13:45", A320);

		// Today's instance exists (the window starts today) but left at 09:30.
		search("2026-01-05").andExpect(jsonPath("$", hasSize(0)));
		book(flightId("XY101", "2026-01-05"), "12A")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("FLIGHT_NOT_BOOKABLE"));
		search("2026-01-06").andExpect(jsonPath("$", hasSize(1)));
	}

	@Test
	void theLastFreeSeatCanBeBookedAndThenTheFlightIsFull() throws Exception {
		createDaily("XY505", "09:30", "13:45", ATR72);
		long flight = flightId("XY505", "2026-01-06");
		List<String> seats = aircraftRepository.findById(ATR72).orElseThrow().seatLayout().seats();
		bookAll(flight, seats.subList(0, 71));

		book(flight, "18D").andExpect(status().isCreated());

		mockMvc.perform(get("/api/v1/flights/{id}/seats", flight))
				.andExpect(jsonPath("$.availableSeats").value(0))
				.andExpect(jsonPath("$.seats[*].status", everyItem(is("BOOKED"))));
		search("2026-01-06", "DOH")
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].availableSeats").value(0));
		book(flight, "18D")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SEAT_UNAVAILABLE"));
		assertThat(availableSeats(flight)).isZero();
		assertCounterMatchesTakenSeats(flight);
	}

	@Test
	void askingForMoreSeatsThanRemainBooksNothing() throws Exception {
		createDaily("XY505", "09:30", "13:45", ATR72);
		long flight = flightId("XY505", "2026-01-06");
		List<String> seats = aircraftRepository.findById(ATR72).orElseThrow().seatLayout().seats();
		bookAll(flight, seats.subList(0, 70)); // 18C and 18D left

		book(flight, "18B", "18C", "18D")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SEAT_UNAVAILABLE"))
				.andExpect(jsonPath("$.unavailableSeats", contains("18B")));

		assertThat(availableSeats(flight)).isEqualTo(2);
		assertCounterMatchesTakenSeats(flight);
	}

	// ---------------------------------------------------------------------------------------

	private void createDaily(String flightNumber, String departure, String arrival, long aircraftId) {
		String destination = aircraftId == ATR72 ? "DOH" : "LHR";
		scheduleService.createSchedule(new CreateScheduleCommand(flightNumber, "DXB", destination,
				LocalTime.parse(departure), LocalTime.parse(arrival), aircraftId, EnumSet.allOf(DayOfWeek.class)));
	}

	/** Books the seats through the API, at most 9 per booking. */
	private void bookAll(long flight, List<String> seats) throws Exception {
		for (int from = 0; from < seats.size(); from += 9) {
			List<String> batch = seats.subList(from, Math.min(from + 9, seats.size()));
			book(flight, batch.toArray(String[]::new)).andExpect(status().isCreated());
		}
	}

	private ResultActions book(long flight, String... seats) throws Exception {
		StringBuilder passengers = new StringBuilder();
		for (String seat : seats) {
			if (!passengers.isEmpty()) {
				passengers.append(',');
			}
			passengers.append("{\"name\": \"Passenger %s\", \"seatNumber\": \"%s\"}".formatted(seat, seat));
		}
		return mockMvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON)
				.content("{\"flightInstanceId\": %d, \"passengers\": [%s]}".formatted(flight, passengers)));
	}

	private ResultActions search(String date) throws Exception {
		return search(date, "LHR");
	}

	private ResultActions search(String date, String destination) throws Exception {
		return mockMvc.perform(get("/api/v1/flights")
				.param("origin", "DXB").param("destination", destination).param("date", date));
	}

	private long flightId(String flightNumber, String date) {
		return jdbcTemplate.queryForObject(
				"SELECT id FROM flight_instance WHERE flight_number = ? AND flight_date = CAST(? AS DATE)", Long.class,
				flightNumber, date);
	}

	private int availableSeats(long flight) {
		return jdbcTemplate.queryForObject("SELECT available_seats FROM flight_instance WHERE id = ?", Integer.class,
				flight);
	}

	private void assertCounterMatchesTakenSeats(long flight) {
		Integer taken = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM booking_seat WHERE flight_instance_id = ? AND status IN ('ACTIVE', 'HELD')",
				Integer.class, flight);
		Integer total = jdbcTemplate.queryForObject("SELECT total_seats FROM flight_instance WHERE id = ?",
				Integer.class, flight);
		assertThat(availableSeats(flight)).isEqualTo(total - taken);
	}

}
