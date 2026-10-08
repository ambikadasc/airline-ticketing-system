package com.airline.reservation.schedule;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.airline.reservation.IntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The admin schedule API, end to end through HTTP and the database. Today is Monday 2026-01-05. */
class ScheduleApiTest extends IntegrationTest {

	private static final String XY101 = """
			{
			  "flightNumber": "XY101",
			  "origin": "DXB",
			  "destination": "LHR",
			  "departureTime": "09:30",
			  "arrivalTime": "13:45",
			  "aircraftId": 1,
			  "daysOfOperation": ["MONDAY", "WEDNESDAY", "FRIDAY"]
			}
			""";

	@Test
	void createsAScheduleAndItsInstancesForTheNextYear() throws Exception {
		createSchedule(XY101)
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/v1/admin/schedules/1"))
				.andExpect(jsonPath("$.id").value(1))
				.andExpect(jsonPath("$.flightNumber").value("XY101"))
				.andExpect(jsonPath("$.origin").value("DXB"))
				.andExpect(jsonPath("$.destination").value("LHR"))
				.andExpect(jsonPath("$.departureTime").value("09:30"))
				.andExpect(jsonPath("$.arrivalTime").value("13:45"))
				.andExpect(jsonPath("$.aircraftId").value(1))
				.andExpect(jsonPath("$.daysOfOperation", contains("MONDAY", "WEDNESDAY", "FRIDAY")))
				.andExpect(jsonPath("$.generatedInstances").value(157));

		assertThat(count("SELECT COUNT(*) FROM flight_instance")).isEqualTo(157);
		// ISO day of week: 1 = Monday, 3 = Wednesday, 5 = Friday
		assertThat(count("SELECT COUNT(*) FROM flight_instance WHERE EXTRACT(ISODOW FROM flight_date) NOT IN (1, 3, 5)"))
				.isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT MIN(flight_date) FROM flight_instance", LocalDate.class))
				.isEqualTo(LocalDate.of(2026, 1, 5));
		assertThat(jdbcTemplate.queryForObject("SELECT MAX(flight_date) FROM flight_instance", LocalDate.class))
				.isEqualTo(LocalDate.of(2027, 1, 4));
		assertThat(count("""
				SELECT COUNT(*) FROM flight_instance
				WHERE departure_at = (flight_date + TIME '09:30') AT TIME ZONE 'UTC'
				  AND arrival_at = (flight_date + TIME '13:45') AT TIME ZONE 'UTC'
				  AND total_seats = 180 AND available_seats = 180
				""")).isEqualTo(157);
	}

	@Test
	void fetchesAScheduleWithoutTheGeneratedCount() throws Exception {
		createSchedule(XY101).andExpect(status().isCreated());

		mockMvc.perform(get("/api/v1/admin/schedules/1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.flightNumber").value("XY101"))
				.andExpect(jsonPath("$.daysOfOperation", contains("MONDAY", "WEDNESDAY", "FRIDAY")))
				.andExpect(jsonPath("$.generatedInstances").doesNotExist());
	}

	@Test
	void unknownScheduleIsNotFound() throws Exception {
		mockMvc.perform(get("/api/v1/admin/schedules/99"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SCHEDULE_NOT_FOUND"));
	}

	@Test
	void overnightFlightArrivesTheNextDay() throws Exception {
		createSchedule(XY101.replace("XY101", "XY202").replace("09:30", "22:00").replace("13:45", "02:15"))
				.andExpect(status().isCreated());

		assertThat(count("""
				SELECT COUNT(*) FROM flight_instance
				WHERE arrival_at = (flight_date + 1 + TIME '02:15') AT TIME ZONE 'UTC'
				""")).isEqualTo(157);
	}

	@Test
	void duplicateFlightNumberIsAConflict() throws Exception {
		createSchedule(XY101).andExpect(status().isCreated());

		createSchedule(XY101)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("DUPLICATE_FLIGHT_NUMBER"));
		assertThat(count("SELECT COUNT(*) FROM flight_schedule")).isEqualTo(1);
	}

	@Test
	void unknownAirportIsNotFound() throws Exception {
		createSchedule(XY101.replace("\"LHR\"", "\"XXX\""))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("AIRPORT_NOT_FOUND"));
	}

	@Test
	void unknownAircraftIsNotFound() throws Exception {
		createSchedule(XY101.replace("\"aircraftId\": 1", "\"aircraftId\": 99"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("AIRCRAFT_NOT_FOUND"));
		assertThat(count("SELECT COUNT(*) FROM flight_schedule")).isZero();
	}

	@Test
	void sameOriginAndDestinationIsRejected() throws Exception {
		createSchedule(XY101.replace("\"LHR\"", "\"DXB\""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[*].field", hasItem("routeDistinct")));
	}

	@Test
	void noOperatingDaysIsRejected() throws Exception {
		createSchedule(XY101.replace("[\"MONDAY\", \"WEDNESDAY\", \"FRIDAY\"]", "[]"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[*].field", hasItem("daysOfOperation")));
	}

	@Test
	void malformedFlightNumberIsRejected() throws Exception {
		createSchedule(XY101.replace("XY101", "XY-101"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[*].field", hasItem("flightNumber")));
	}

	@Test
	void missingFieldIsRejected() throws Exception {
		createSchedule(XY101.replace("\"aircraftId\": 1,", ""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[*].field", hasItem("aircraftId")));
	}

	@Test
	void unknownDayNameIsAMalformedRequest() throws Exception {
		createSchedule(XY101.replace("\"FRIDAY\"", "\"FUNDAY\""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		assertThat(count("SELECT COUNT(*) FROM flight_schedule")).isZero();
	}

	private ResultActions createSchedule(String body) throws Exception {
		return mockMvc.perform(post("/api/v1/admin/schedules").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private long count(String sql) {
		return jdbcTemplate.queryForObject(sql, Long.class);
	}

}
