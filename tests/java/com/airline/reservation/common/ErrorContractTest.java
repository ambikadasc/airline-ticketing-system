package com.airline.reservation.common;

import java.io.UnsupportedEncodingException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.airline.reservation.IntegrationTest;
import com.airline.reservation.booking.domain.PassengerSeat;
import com.airline.reservation.booking.service.BookingService;
import com.airline.reservation.booking.service.CreateBookingCommand;
import com.airline.reservation.schedule.service.CreateScheduleCommand;
import com.airline.reservation.schedule.service.ScheduleService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Every error code reachable over HTTP (seat hold off) comes back in the same shape: a ProblemDetail
 * with status, title, detail, code and the request id, and nothing internal in it.
 * HOLD_EXPIRED needs the seat hold and is checked in SeatHoldTest; LOCK_TIMEOUT and the pool
 * exhaustion case have their own tests.
 */
class ErrorContractTest extends IntegrationTest {

	private static final List<String> NEVER_IN_AN_ERROR_BODY = List.of(
			"Exception", "java.", "tools.jackson", "org.", "SQL", "uq_", "chk_");

	@Autowired
	private ScheduleService scheduleService;

	@Autowired
	private BookingService bookingService;

	private long flightId;
	private long departedFlightId;
	private String bookingReference;
	private String cancelledReference;

	@BeforeEach
	void createFlightsAndBookings() {
		scheduleService.createSchedule(schedule("XY101", "09:30", "13:45"));
		// Departs at 00:00: today's flight left at exactly "now".
		scheduleService.createSchedule(schedule("XY303", "00:00", "04:00"));
		flightId = flightId("XY101", "2026-01-06");
		departedFlightId = flightId("XY303", "2026-01-05");
		bookingReference = book(flightId, "12A");
		cancelledReference = book(flightId, "12B");
		bookingService.cancelBooking(cancelledReference);
	}

	@TestFactory
	Stream<DynamicTest> everyErrorHasTheSameShape() {
		return Stream.of(
				error("body field invalid", 400, "VALIDATION_ERROR",
						json(post("/api/v1/bookings"), "{\"flightInstanceId\": %d, \"passengers\": []}".formatted(flightId))),
				error("query parameter invalid", 400, "VALIDATION_ERROR",
						get("/api/v1/flights").param("origin", "dxb").param("destination", "LHR").param("date", "2026-01-06")),
				error("query parameter missing", 400, "VALIDATION_ERROR",
						get("/api/v1/flights").param("origin", "DXB").param("destination", "LHR")),
				error("malformed JSON names the field", 400, "VALIDATION_ERROR",
						json(post("/api/v1/admin/schedules"), scheduleJson("XY909", 1, "\"MONDAY\", \"WEDNESDAY\", \"FUNDAY\"")),
						body -> assertThat(detail(body)).isEqualTo("Invalid value for field 'daysOfOperation[2]'")),
				error("unparseable JSON", 400, "VALIDATION_ERROR",
						json(post("/api/v1/bookings"), "{not json")),
				error("date outside the booking window", 400, "OUTSIDE_BOOKING_WINDOW",
						get("/api/v1/flights").param("origin", "DXB").param("destination", "LHR").param("date", "2025-12-31")),
				error("seat not on the aircraft", 400, "INVALID_SEAT", bookingRequest(flightId, "31A")),
				error("unknown airport", 404, "AIRPORT_NOT_FOUND",
						get("/api/v1/flights").param("origin", "DXB").param("destination", "XXX").param("date", "2026-01-06")),
				error("unknown aircraft", 404, "AIRCRAFT_NOT_FOUND",
						json(post("/api/v1/admin/schedules"), scheduleJson("XY909", 99, "\"MONDAY\""))),
				error("unknown schedule", 404, "SCHEDULE_NOT_FOUND", get("/api/v1/admin/schedules/999")),
				error("unknown flight", 404, "FLIGHT_NOT_FOUND", get("/api/v1/flights/999999/seats")),
				error("unknown booking", 404, "BOOKING_NOT_FOUND", get("/api/v1/bookings/ZZZZZZ")),
				error("unknown path", 404, "RESOURCE_NOT_FOUND", get("/api/v1/nope"),
						body -> assertThat(detail(body)).isEqualTo("No endpoint matches this path")),
				error("wrong HTTP method", 405, "METHOD_NOT_ALLOWED", delete("/api/v1/bookings/" + bookingReference),
						response -> assertThat(response.getHeader("Allow")).contains("GET")),
				error("unsupported content type", 415, "UNSUPPORTED_MEDIA_TYPE",
						post("/api/v1/bookings").contentType(MediaType.TEXT_PLAIN).content("12A")),
				error("seat already booked", 409, "SEAT_UNAVAILABLE", bookingRequest(flightId, "12A")),
				error("flight number taken", 409, "DUPLICATE_FLIGHT_NUMBER",
						json(post("/api/v1/admin/schedules"), scheduleJson("XY101", 1, "\"MONDAY\""))),
				error("flight departed (booking)", 409, "FLIGHT_NOT_BOOKABLE", bookingRequest(departedFlightId, "1A")),
				error("confirming a cancelled booking", 409, "BOOKING_NOT_CONFIRMABLE",
						post("/api/v1/bookings/" + cancelledReference + "/confirm")),
				DynamicTest.dynamicTest("flight departed (cancellation) -> 409 BOOKING_NOT_CANCELLABLE", () -> {
					clock.set(Instant.parse("2026-01-06T10:00:00Z")); // after XY101's 09:30 departure
					try {
						assertContract(post("/api/v1/bookings/" + bookingReference + "/cancel"), 409,
								"BOOKING_NOT_CANCELLABLE", response -> { });
					}
					finally {
						clock.reset();
					}
				}));
	}

	@TestFactory
	Stream<DynamicTest> tooManyUnsuccessfulLookupsAreRefused() {
		return Stream.of(DynamicTest.dynamicTest("11th unsuccessful lookup in a minute -> 429 RATE_LIMITED", () -> {
			for (int i = 0; i < 10; i++) {
				mockMvc.perform(get("/api/v1/bookings/ZZZZZZ"));
			}
			assertContract(get("/api/v1/bookings/ZZZZZZ"), 429, "RATE_LIMITED",
					response -> assertThat(response.getHeader("Retry-After")).isEqualTo("60"));
		}));
	}

	@TestFactory
	Stream<DynamicTest> aReusedIdempotencyKeyIsRefused() {
		return Stream.of(DynamicTest.dynamicTest("Idempotency-Key reused for another request -> 422 IDEMPOTENCY_KEY_REUSED",
				() -> {
					mockMvc.perform(((MockHttpServletRequestBuilder) bookingRequest(flightId, "12C"))
							.header("Idempotency-Key", "contract-key"));
					assertContract(((MockHttpServletRequestBuilder) bookingRequest(flightId, "12D"))
							.header("Idempotency-Key", "contract-key"), 422, "IDEMPOTENCY_KEY_REUSED", response -> { });
				}));
	}

	@TestFactory
	Stream<DynamicTest> notAcceptableIsReportedToo() {
		return Stream.of(error("only JSON can be produced", 406, "NOT_ACCEPTABLE",
				get("/api/v1/admin/schedules/1").accept(MediaType.TEXT_PLAIN)));
	}

	// ---------------------------------------------------------------------------------------

	private DynamicTest error(String name, int status, String code, RequestBuilder request) {
		return error(name, status, code, request, response -> { });
	}

	private DynamicTest error(String name, int status, String code, RequestBuilder request,
			Consumer<MockHttpServletResponse> extraChecks) {
		return DynamicTest.dynamicTest(name + " -> " + status + " " + code,
				() -> assertContract(request, status, code, extraChecks));
	}

	private void assertContract(RequestBuilder request, int status, String code,
			Consumer<MockHttpServletResponse> extraChecks) throws Exception {
		MockHttpServletResponse response = mockMvc.perform(request).andReturn().getResponse();
		String body = response.getContentAsString();

		assertThat(response.getStatus()).as(body).isEqualTo(status);
		assertThat(response.getContentType()).isEqualTo("application/problem+json");
		assertThat((String) JsonPath.read(body, "$.code")).isEqualTo(code);
		assertThat((Integer) JsonPath.read(body, "$.status")).isEqualTo(status);
		assertThat((String) JsonPath.read(body, "$.title")).isNotBlank();
		assertThat(detail(body)).isNotBlank();
		assertThat((String) JsonPath.read(body, "$.requestId")).isEqualTo(response.getHeader("X-Request-Id"));
		assertThat(body).doesNotContain(NEVER_IN_AN_ERROR_BODY);
		extraChecks.accept(response);
	}

	private static String detail(MockHttpServletResponse response) {
		try {
			return detail(response.getContentAsString());
		}
		catch (UnsupportedEncodingException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String detail(String body) {
		return JsonPath.read(body, "$.detail");
	}

	private static RequestBuilder json(MockHttpServletRequestBuilder builder,
			String body) {
		return builder.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private static RequestBuilder bookingRequest(long flight, String seat) {
		return json(post("/api/v1/bookings"), """
				{"flightInstanceId": %d, "passengers": [{"name": "Test Passenger", "seatNumber": "%s"}]}
				""".formatted(flight, seat));
	}

	private static String scheduleJson(String flightNumber, long aircraftId, String days) {
		return """
				{"flightNumber": "%s", "origin": "DXB", "destination": "LHR", "departureTime": "09:30",
				 "arrivalTime": "13:45", "aircraftId": %d, "daysOfOperation": [%s]}
				""".formatted(flightNumber, aircraftId, days);
	}

	private static CreateScheduleCommand schedule(String flightNumber, String departure, String arrival) {
		return new CreateScheduleCommand(flightNumber, "DXB", "LHR", LocalTime.parse(departure),
				LocalTime.parse(arrival), 1L, EnumSet.allOf(DayOfWeek.class));
	}

	private long flightId(String flightNumber, String date) {
		return jdbcTemplate.queryForObject(
				"SELECT id FROM flight_instance WHERE flight_number = ? AND flight_date = CAST(? AS DATE)", Long.class,
				flightNumber, date);
	}

	private String book(long flight, String seat) {
		return bookingService.createBooking(new CreateBookingCommand(flight,
				List.of(new PassengerSeat("Test Passenger", seat)))).bookingReference();
	}

}
