package com.airline.reservation.booking;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;

import com.airline.reservation.IntegrationTest;
import com.airline.reservation.booking.persistence.BookingRepository;
import com.airline.reservation.booking.service.PnrGenerator;
import com.airline.reservation.schedule.service.CreateScheduleCommand;
import com.airline.reservation.schedule.service.ScheduleService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two bookings on different flights draw the same random reference. In production that needs two
 * requests in flight at the same instant drawing the same 1-in-a-billion code; here the generator
 * is fixed and the "already used?" check is made to miss, which is exactly what the race looks like.
 * The real unique constraint then rejects the second insert: nothing is booked, and the client is
 * told to retry (503), never shown a 500.
 */
class BookingReferenceClashTest extends IntegrationTest {

	@MockitoBean
	private PnrGenerator pnrGenerator;

	@MockitoSpyBean
	private BookingRepository bookingRepository;

	@Autowired
	private ScheduleService scheduleService;

	private long firstFlight;
	private long secondFlight;

	@BeforeEach
	void twoFlightsAndAFixedReference() {
		scheduleService.createSchedule(new CreateScheduleCommand("XY101", "DXB", "LHR", LocalTime.parse("09:30"),
				LocalTime.parse("13:45"), 1L, EnumSet.allOf(DayOfWeek.class)));
		firstFlight = flightOn("2026-01-06");
		secondFlight = flightOn("2026-01-07");
		when(pnrGenerator.next()).thenReturn("AAAAAA");
	}

	@Test
	void aClashOnTheReferenceIsARetryableServiceUnavailable() throws Exception {
		// The check misses the clash, as it would while the other booking is not yet committed.
		doReturn(false).when(bookingRepository).existsByReference(anyString());
		book(firstFlight).andExpect(status().isCreated()).andExpect(jsonPath("$.bookingReference").value("AAAAAA"));

		book(secondFlight)
				.andExpect(status().isServiceUnavailable())
				.andExpect(header().string("Retry-After", "1"))
				.andExpect(jsonPath("$.code").value("RETRY_LATER"))
				.andExpect(jsonPath("$.requestId").isNotEmpty());

		// Nothing from the second request was stored.
		assertThat(count("SELECT COUNT(*) FROM booking")).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM booking_seat WHERE flight_instance_id = " + secondFlight)).isZero();
		assertThat(count("SELECT available_seats FROM flight_instance WHERE id = " + secondFlight)).isEqualTo(180);
	}

	@Test
	void runningOutOfReferenceDrawsIsAlsoRetryable() throws Exception {
		// The real check sees AAAAAA taken on all five draws.
		book(firstFlight).andExpect(status().isCreated());

		book(secondFlight)
				.andExpect(status().isServiceUnavailable())
				.andExpect(header().string("Retry-After", "1"))
				.andExpect(jsonPath("$.code").value("RETRY_LATER"));
		assertThat(count("SELECT COUNT(*) FROM booking")).isEqualTo(1);
	}

	private ResultActions book(long flight) throws Exception {
		return mockMvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON).content("""
				{"flightInstanceId": %d, "passengers": [{"name": "Ayesha Khan", "seatNumber": "12A"}]}
				""".formatted(flight)));
	}

	private long flightOn(String date) {
		return jdbcTemplate.queryForObject("SELECT id FROM flight_instance WHERE flight_date = CAST(? AS DATE)",
				Long.class, date);
	}

	private int count(String sql) {
		return jdbcTemplate.queryForObject(sql, Integer.class);
	}

}
