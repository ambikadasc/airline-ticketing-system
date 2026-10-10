package com.airline.reservation.booking;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;

import com.airline.reservation.IntegrationTest;
import com.airline.reservation.booking.ConcurrencySupport.Outcome;
import com.airline.reservation.booking.domain.PassengerSeat;
import com.airline.reservation.booking.service.BookingService;
import com.airline.reservation.booking.service.CreateBookingCommand;
import com.airline.reservation.schedule.service.CreateScheduleCommand;
import com.airline.reservation.schedule.service.ScheduleService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A client that times out and retries POST /bookings with the same Idempotency-Key gets the booking
 * its first attempt created, not a seat conflict. The key is optional: without it, behaviour is
 * unchanged. Today (the fixed test clock) is 2026-01-05; the flight is on 2026-01-06.
 */
class BookingIdempotencyTest extends IntegrationTest {

	private static final String KEY = "order-7f3a2c";

	@Autowired
	private ScheduleService scheduleService;

	@Autowired
	private BookingService bookingService;

	private ConcurrencySupport concurrency;

	private long flightId;
	private long otherFlightId;

	@BeforeEach
	void createFlights() {
		concurrency = new ConcurrencySupport(jdbcTemplate);
		scheduleService.createSchedule(new CreateScheduleCommand("XY101", "DXB", "LHR", LocalTime.parse("09:30"),
				LocalTime.parse("13:45"), 1L, EnumSet.allOf(DayOfWeek.class)));
		flightId = flightOn("2026-01-06");
		otherFlightId = flightOn("2026-01-07");
	}

	@AfterEach
	void stopThreads() {
		concurrency.close();
	}

	@Test
	void theSameKeyAndRequestReturnsTheSameBookingWithoutBookingAgain() throws Exception {
		MockHttpServletResponse first = book(KEY, flightId, "12A").andExpect(status().isCreated())
				.andReturn().getResponse();
		String reference = JsonPath.read(first.getContentAsString(), "$.bookingReference");

		book(KEY, flightId, "12A")
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/v1/bookings/" + reference))
				.andExpect(jsonPath("$.bookingReference").value(reference))
				.andExpect(jsonPath("$.status").value("CONFIRMED"))
				.andExpect(content().json(first.getContentAsString()));

		assertThat(bookingCount()).isEqualTo(1);
		assertThat(concurrency.availableSeats(flightId)).isEqualTo(179);
		concurrency.assertSeatInventoryConsistent(flightId);
	}

	@Test
	void theSameKeyWithADifferentRequestIsRefused() throws Exception {
		book(KEY, flightId, "12A").andExpect(status().isCreated());

		book(KEY, flightId, "12C")
				.andExpect(status().isUnprocessableContent())
				.andExpect(content().contentType("application/problem+json"))
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
				.andExpect(jsonPath("$.requestId").isNotEmpty());
		book(KEY, otherFlightId, "12A")
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

		assertThat(bookingCount()).isEqualTo(1);
		assertThat(concurrency.activeSeatCount(flightId)).isEqualTo(1);
	}

	@Test
	void withoutAKeyARetryMeetsTheSeatConflictAsBefore() throws Exception {
		book(null, flightId, "12A").andExpect(status().isCreated());

		book(null, flightId, "12A")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SEAT_UNAVAILABLE"));
	}

	@Test
	void aDifferentKeyIsADifferentRequestEvenForTheSameSeats() throws Exception {
		book(KEY, flightId, "12A").andExpect(status().isCreated());

		book("another-key", flightId, "12A")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SEAT_UNAVAILABLE"));
		assertThat(bookingCount()).isEqualTo(1);
	}

	@Test
	void aReplayReturnsTheBookingAsItIsNow() throws Exception {
		String reference = JsonPath.read(book(KEY, flightId, "12A").andReturn().getResponse().getContentAsString(),
				"$.bookingReference");
		mockMvc.perform(post("/api/v1/bookings/{reference}/cancel", reference)).andExpect(status().isOk());

		// The key identifies the booking the request created; its status has moved on since.
		book(KEY, flightId, "12A")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.bookingReference").value(reference))
				.andExpect(jsonPath("$.status").value("CANCELLED"));
		assertThat(bookingCount()).isEqualTo(1);
	}

	@Test
	void aMalformedKeyIsRejectedBeforeAnythingIsBooked() throws Exception {
		book("not a valid key!", flightId, "12A")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		assertThat(bookingCount()).isZero();
	}

	/** Twenty retries of one request released at the same instant: one booking, and every caller learns its reference. */
	@Test
	void concurrentRetriesAllGetTheOneBooking() throws Exception {
		Set<String> references = ConcurrentHashMap.newKeySet();
		CreateBookingCommand command = new CreateBookingCommand(flightId,
				List.of(new PassengerSeat("Ayesha Khan", "12A")), KEY);

		List<Outcome> outcomes = concurrency.runConcurrently(20,
				i -> () -> references.add(bookingService.createBooking(command).bookingReference()));

		assertThat(ConcurrencySupport.count(outcomes, Outcome.SUCCESS)).isEqualTo(20);
		assertThat(references).hasSize(1);
		assertThat(bookingCount()).isEqualTo(1);
		concurrency.assertSeatInventoryConsistent(flightId);
	}

	/**
	 * The one case the flight lock cannot serialise: the same key used at the same instant for two
	 * different flights. The unique index on the key decides; the loser is told the key was reused.
	 */
	@Test
	void theSameKeyOnTwoFlightsAtOnceCreatesOneBooking() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			CountDownLatch startLine = new CountDownLatch(1);
			List<Future<Integer>> statuses = List.of(
					executor.submit(() -> {
						startLine.await();
						return book(KEY, flightId, "12A").andReturn().getResponse().getStatus();
					}),
					executor.submit(() -> {
						startLine.await();
						return book(KEY, otherFlightId, "12A").andReturn().getResponse().getStatus();
					}));
			startLine.countDown();
			List<Integer> results = List.of(statuses.get(0).get(60, TimeUnit.SECONDS),
					statuses.get(1).get(60, TimeUnit.SECONDS));

			assertThat(results).containsExactlyInAnyOrder(201, 422);
			assertThat(bookingCount()).isEqualTo(1);
		}
		finally {
			executor.shutdownNow();
		}
	}

	// ---------------------------------------------------------------------------------------

	private ResultActions book(String key, long flight, String seat) throws Exception {
		var request = post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON).content("""
				{"flightInstanceId": %d, "passengers": [{"name": "Ayesha Khan", "seatNumber": "%s"}]}
				""".formatted(flight, seat));
		if (key != null) {
			request.header("Idempotency-Key", key);
		}
		return mockMvc.perform(request);
	}

	private int bookingCount() {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM booking", Integer.class);
	}

	private long flightOn(String date) {
		return jdbcTemplate.queryForObject("SELECT id FROM flight_instance WHERE flight_date = CAST(? AS DATE)",
				Long.class, date);
	}

}
