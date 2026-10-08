package com.airline.reservation.schedule;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.airline.reservation.IntegrationTest;
import com.airline.reservation.schedule.service.CreateScheduleCommand;
import com.airline.reservation.schedule.service.ScheduleService;

import static org.assertj.core.api.Assertions.assertThat;

/** The window job is idempotent and fills gaps (e.g. after downtime) without duplicating. */
class InstanceWindowJobTest extends IntegrationTest {

	@Autowired
	private ScheduleService scheduleService;

	@Test
	void runningTheJobAgainInsertsNothing() {
		scheduleService.createSchedule(dailyXy101());

		assertThat(scheduleService.extendInstanceWindow().inserted()).isZero();
		assertThat(scheduleService.extendInstanceWindow().inserted()).isZero();
		assertThat(instanceCount()).isEqualTo(366);
	}

	@Test
	void theJobFillsMissingDates() {
		scheduleService.createSchedule(dailyXy101());
		jdbcTemplate.update("""
				DELETE FROM flight_instance
				WHERE flight_date > (SELECT MAX(flight_date) - 10 FROM flight_instance)
				""");
		assertThat(instanceCount()).isEqualTo(356);

		assertThat(scheduleService.extendInstanceWindow().inserted()).isEqualTo(10);
		assertThat(instanceCount()).isEqualTo(366);
	}

	private CreateScheduleCommand dailyXy101() {
		return new CreateScheduleCommand("XY101", "DXB", "LHR", LocalTime.parse("09:30"), LocalTime.parse("13:45"),
				1L, EnumSet.allOf(DayOfWeek.class));
	}

	private long instanceCount() {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM flight_instance", Long.class);
	}

}
