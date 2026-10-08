package com.airline.reservation.schedule.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static java.time.DayOfWeek.FRIDAY;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.WEDNESDAY;
import static org.assertj.core.api.Assertions.assertThat;

class FlightScheduleTest {

	private static final Instant NOW = Instant.parse("2026-01-05T00:00:00Z");

	@Test
	void arrivalAfterDepartureIsTheSameDay() {
		assertThat(schedule("09:30", "13:45").getArrivalDayOffset()).isZero();
	}

	@Test
	void arrivalBeforeDepartureIsTheNextDay() {
		assertThat(schedule("22:00", "02:15").getArrivalDayOffset()).isEqualTo(1);
	}

	@Test
	void arrivalAtTheSameTimeAsDepartureIsTheNextDay() {
		assertThat(schedule("10:00", "10:00").getArrivalDayOffset()).isEqualTo(1);
	}

	@Test
	void keepsItsOwnCopyOfTheOperatingDays() {
		Set<DayOfWeek> days = EnumSet.of(MONDAY, WEDNESDAY);
		FlightSchedule schedule = FlightSchedule.create("XY101", "DXB", "LHR",
				LocalTime.parse("09:30"), LocalTime.parse("13:45"), 1L, days, NOW);

		days.add(FRIDAY);

		assertThat(schedule.getDaysOfOperation()).containsExactlyInAnyOrder(MONDAY, WEDNESDAY);
	}

	private static FlightSchedule schedule(String departure, String arrival) {
		return FlightSchedule.create("XY101", "DXB", "LHR", LocalTime.parse(departure), LocalTime.parse(arrival),
				1L, EnumSet.of(MONDAY), NOW);
	}

}
