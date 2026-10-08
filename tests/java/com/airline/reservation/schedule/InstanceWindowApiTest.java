package com.airline.reservation.schedule;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import com.airline.reservation.IntegrationTest;
import com.airline.reservation.schedule.service.CreateScheduleCommand;
import com.airline.reservation.schedule.service.ScheduleService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The manual trigger for instance generation. Today (the test clock) is 2026-01-05; the window ends 2027-01-05. */
class InstanceWindowApiTest extends IntegrationTest {

	@Autowired
	private ScheduleService scheduleService;

	@Test
	void fillsMissingDatesAndReportsHowFarTheWindowReaches() throws Exception {
		createDailySchedule();
		jdbcTemplate.update("DELETE FROM flight_instance WHERE flight_date > DATE '2026-12-26'");
		assertThat(instanceCount()).isEqualTo(356);

		extend()
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.inserted").value(10))
				.andExpect(jsonPath("$.windowEnd").value("2027-01-05"));
		assertThat(instanceCount()).isEqualTo(366);
	}

	@Test
	void runningItAgainInsertsNothing() throws Exception {
		createDailySchedule();

		extend().andExpect(status().isOk()).andExpect(jsonPath("$.inserted").value(0));
		extend().andExpect(status().isOk()).andExpect(jsonPath("$.inserted").value(0));
		assertThat(instanceCount()).isEqualTo(366);
	}

	@Test
	void withNoSchedulesThereIsNothingToGenerate() throws Exception {
		extend()
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.inserted").value(0))
				.andExpect(jsonPath("$.windowEnd").value("2027-01-05"));
	}

	@Test
	void onlyPostIsAllowed() throws Exception {
		mockMvc.perform(get("/api/v1/admin/instance-window/extend"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
				.andExpect(jsonPath("$.requestId").isNotEmpty());
	}

	private ResultActions extend() throws Exception {
		return mockMvc.perform(post("/api/v1/admin/instance-window/extend"));
	}

	private void createDailySchedule() {
		scheduleService.createSchedule(new CreateScheduleCommand("XY101", "DXB", "LHR", LocalTime.parse("09:30"),
				LocalTime.parse("13:45"), 1L, EnumSet.allOf(DayOfWeek.class)));
	}

	private int instanceCount() {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM flight_instance", Integer.class);
	}

}
