package com.airline.reservation.booking;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import com.airline.reservation.IntegrationTest;
import com.airline.reservation.TestcontainersConfiguration;
import com.airline.reservation.booking.ConcurrencySupport.Outcome;
import com.airline.reservation.booking.domain.PassengerSeat;
import com.airline.reservation.booking.service.BookingService;
import com.airline.reservation.booking.service.CreateBookingCommand;
import com.airline.reservation.schedule.service.CreateScheduleCommand;
import com.airline.reservation.schedule.service.ScheduleService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The optional seat hold, with {@code airline.seat-hold.enabled=true} (its own Spring context).
 * Holds last 10 minutes. Now starts at 2026-01-05 00:00 UTC; the flight departs 2026-01-06 09:30.
 */
@TestPropertySource(properties = "airline.seat-hold.enabled=true")
class SeatHoldTest extends IntegrationTest {

	private static final Instant HOLD_EXPIRY = TestcontainersConfiguration.TEST_NOW.plus(Duration.ofMinutes(10));

	@Autowired
	private ScheduleService scheduleService;

	@Autowired
	private BookingService bookingService;

	private ConcurrencySupport concurrency;

	private long flightId;

	@BeforeEach
	void createFlight() {
		concurrency = new ConcurrencySupport(jdbcTemplate);
		scheduleService.createSchedule(new CreateScheduleCommand("XY101", "DXB", "LHR", LocalTime.parse("09:30"),
				LocalTime.parse("13:45"), 1L, EnumSet.allOf(DayOfWeek.class)));
		flightId = jdbcTemplate.queryForObject(
				"SELECT id FROM flight_instance WHERE flight_date = DATE '2026-01-06'", Long.class);
	}

	@AfterEach
	void stopThreads() {
		concurrency.close();
	}

	@Test
	void aBookingIsHeldUntilConfirmed() throws Exception {
		String reference = bookAndExpect("HELD", "12A", "12B");
		mockMvc.perform(get("/api/v1/bookings/{reference}", reference))
				.andExpect(jsonPath("$.status").value("HELD"))
				.andExpect(jsonPath("$.holdExpiresAt").value("2026-01-05T00:10:00Z"));

		// Held seats are taken for everyone else.
		mockMvc.perform(get("/api/v1/flights/{id}/seats", flightId))
				.andExpect(jsonPath("$.seats[?(@.seatNumber == '12A')].status", contains("BOOKED")));
		assertThat(searchAvailableSeats()).isEqualTo(178);

		confirm(reference)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CONFIRMED"))
				.andExpect(jsonPath("$.holdExpiresAt").doesNotExist());
		confirm(reference).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
		assertThat(searchAvailableSeats()).isEqualTo(178);
		concurrency.assertSeatInventoryConsistent(flightId);
	}

	@Test
	void anExpiredHoldFreesItsSeatsForTheNextCustomer() throws Exception {
		String reference = bookAndExpect("HELD", "12A", "12B");
		clock.advance(Duration.ofMinutes(11));

		// Before any write releases it, reads already treat the hold as expired.
		mockMvc.perform(get("/api/v1/bookings/{reference}", reference))
				.andExpect(jsonPath("$.status").value("EXPIRED"))
				.andExpect(jsonPath("$.holdExpiresAt").doesNotExist());
		mockMvc.perform(get("/api/v1/flights/{id}/seats", flightId))
				.andExpect(jsonPath("$.availableSeats").value(180))
				.andExpect(jsonPath("$.seats[?(@.seatNumber == '12A')].status", contains("AVAILABLE")));
		assertThat(searchAvailableSeats()).isEqualTo(180);

		// The next booking on the flight releases the expired hold, then takes the seat.
		bookAndExpect("HELD", "12A");

		assertThat(jdbcTemplate.queryForObject("SELECT status FROM booking WHERE reference = ?", String.class,
				reference)).isEqualTo("EXPIRED");
		assertThat(jdbcTemplate.queryForList("""
				SELECT s.status FROM booking_seat s JOIN booking b ON b.id = s.booking_id WHERE b.reference = ?
				""", String.class, reference)).containsOnly("RELEASED");
		assertThat(concurrency.availableSeats(flightId)).isEqualTo(179);
		concurrency.assertSeatInventoryConsistent(flightId);
	}

	@Test
	void anExpiredHoldCannotBeConfirmed() throws Exception {
		String reference = bookAndExpect("HELD", "12A");
		clock.set(HOLD_EXPIRY);

		confirm(reference)
				.andExpect(status().isConflict())
				.andExpect(content().contentType("application/problem+json"))
				.andExpect(jsonPath("$.code").value("HOLD_EXPIRED"))
				.andExpect(jsonPath("$.title").isNotEmpty())
				.andExpect(jsonPath("$.detail").isNotEmpty())
				.andExpect(jsonPath("$.requestId").isNotEmpty());
	}

	@Test
	void aHeldBookingCanBeCancelledAndThenNotConfirmed() throws Exception {
		String reference = bookAndExpect("HELD", "12A", "12B");

		mockMvc.perform(post("/api/v1/bookings/{reference}/cancel", reference))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELLED"));
		assertThat(concurrency.availableSeats(flightId)).isEqualTo(180);

		confirm(reference)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("BOOKING_NOT_CONFIRMABLE"));
		concurrency.assertSeatInventoryConsistent(flightId);
	}

	@Test
	void cancellingAnExpiredHoldReturnsItUnchangedAndReleasesOnce() throws Exception {
		String reference = bookAndExpect("HELD", "12A", "12B");
		clock.advance(Duration.ofMinutes(11));

		mockMvc.perform(post("/api/v1/bookings/{reference}/cancel", reference))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("EXPIRED"));
		mockMvc.perform(post("/api/v1/bookings/{reference}/cancel", reference))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("EXPIRED"));

		assertThat(concurrency.availableSeats(flightId)).isEqualTo(180);
		concurrency.assertSeatInventoryConsistent(flightId);
	}

	/**
	 * Confirmation racing the hold's expiry. Each round makes a fresh hold, sets the clock just before
	 * or just after its expiry, then one thread confirms while another customer books the same seat.
	 * Whatever order the lock gives them, exactly one wins, and the clock decides which.
	 */
	@Test
	void confirmationAndExpiryNeverBothWin() throws Exception {
		for (int round = 0; round < 20; round++) {
			clock.reset();
			String seat = (round / 6 + 1) + String.valueOf("ABCDEF".charAt(round % 6));
			String reference = bookingService.createBooking(command(seat)).bookingReference();
			boolean beforeExpiry = round % 2 == 0;
			clock.set(beforeExpiry ? HOLD_EXPIRY.minusSeconds(1) : HOLD_EXPIRY.plusSeconds(1));

			List<Outcome> outcomes = concurrency.runConcurrently(2, i -> i == 0
					? () -> bookingService.confirmBooking(reference)
					: () -> bookingService.createBooking(command(seat)));

			Outcome confirm = outcomes.get(0);
			Outcome otherCustomer = outcomes.get(1);
			if (beforeExpiry) {
				assertThat(confirm).as("round %d confirm", round).isEqualTo(Outcome.SUCCESS);
				assertThat(otherCustomer).as("round %d other customer", round).isEqualTo(Outcome.SEAT_UNAVAILABLE);
			}
			else {
				assertThat(confirm).as("round %d confirm", round).isEqualTo(Outcome.HOLD_EXPIRED);
				assertThat(otherCustomer).as("round %d other customer", round).isEqualTo(Outcome.SUCCESS);
			}
			concurrency.assertSeatInventoryConsistent(flightId);
		}
	}

	@Test
	void aRetryWithTheSameKeyReturnsTheSameHoldAndLaterTheConfirmedBooking() throws Exception {
		String body = "{\"flightInstanceId\": %d, \"passengers\": [{\"name\": \"Passenger 12A\", \"seatNumber\": \"12A\"}]}"
				.formatted(flightId);
		String reference = JsonPath.read(mockMvc.perform(post("/api/v1/bookings").header("Idempotency-Key", "hold-key-1")
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("HELD"))
				.andReturn().getResponse().getContentAsString(), "$.bookingReference");

		mockMvc.perform(post("/api/v1/bookings").header("Idempotency-Key", "hold-key-1")
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.bookingReference").value(reference))
				.andExpect(jsonPath("$.status").value("HELD"))
				.andExpect(jsonPath("$.holdExpiresAt").value("2026-01-05T00:10:00Z"));
		assertThat(searchAvailableSeats()).isEqualTo(179);

		confirm(reference).andExpect(status().isOk());
		mockMvc.perform(post("/api/v1/bookings").header("Idempotency-Key", "hold-key-1")
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.bookingReference").value(reference))
				.andExpect(jsonPath("$.status").value("CONFIRMED"))
				.andExpect(jsonPath("$.holdExpiresAt").doesNotExist());
		concurrency.assertSeatInventoryConsistent(flightId);
	}

	// ---------------------------------------------------------------------------------------

	private String bookAndExpect(String expectedStatus, String... seats) throws Exception {
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
				.andExpect(jsonPath("$.status").value(expectedStatus))
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(response, "$.bookingReference");
	}

	private ResultActions confirm(String reference) throws Exception {
		return mockMvc.perform(post("/api/v1/bookings/{reference}/confirm", reference));
	}

	private int searchAvailableSeats() throws Exception {
		String response = mockMvc.perform(get("/api/v1/flights").param("origin", "DXB").param("destination", "LHR")
						.param("date", "2026-01-06"))
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(response, "$[0].availableSeats");
	}

	private CreateBookingCommand command(String seat) {
		return new CreateBookingCommand(flightId, List.of(new PassengerSeat("Passenger " + seat, seat)));
	}

}
