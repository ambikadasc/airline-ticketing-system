package com.airline.reservation.schedule.service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.airline.reservation.aircraft.SeatLayout;
import com.airline.reservation.flight.domain.FlightInstance;
import com.airline.reservation.schedule.domain.FlightSchedule;

import static java.time.DayOfWeek.FRIDAY;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.WEDNESDAY;
import static org.assertj.core.api.Assertions.assertThat;

class FlightInstanceGeneratorTest {

	private static final SeatLayout A320 = new SeatLayout(30, "ABCDEF");
	private static final LocalDate MONDAY_5_JAN_2026 = LocalDate.of(2026, 1, 5);
	private static final LocalDate ONE_YEAR_LATER = MONDAY_5_JAN_2026.plusDays(365);

	@Test
	void generatesOnlyTheOperatingDaysAcrossTheYear() {
		List<FlightInstance> instances = FlightInstanceGenerator.generate(
				xy101(EnumSet.of(MONDAY, WEDNESDAY, FRIDAY)), A320, MONDAY_5_JAN_2026, ONE_YEAR_LATER);

		assertThat(instances).hasSize(157);
		assertThat(instances).extracting(instance -> instance.getFlightDate().getDayOfWeek())
				.containsOnly(MONDAY, WEDNESDAY, FRIDAY);
	}

	@Test
	void includesBothEndsOfTheWindow() {
		List<FlightInstance> mwf = FlightInstanceGenerator.generate(
				xy101(EnumSet.of(MONDAY, WEDNESDAY, FRIDAY)), A320, MONDAY_5_JAN_2026, ONE_YEAR_LATER);
		List<FlightInstance> daily = FlightInstanceGenerator.generate(
				xy101(EnumSet.allOf(DayOfWeek.class)), A320, MONDAY_5_JAN_2026, ONE_YEAR_LATER);

		assertThat(mwf.getFirst().getFlightDate()).isEqualTo(MONDAY_5_JAN_2026);
		assertThat(mwf.getLast().getFlightDate()).isEqualTo(LocalDate.of(2027, 1, 4));
		assertThat(daily).hasSize(366);
		assertThat(daily.getLast().getFlightDate()).isEqualTo(ONE_YEAR_LATER);
	}

	@Test
	void includesTheLeapDay() {
		List<FlightInstance> february2028 = FlightInstanceGenerator.generate(
				xy101(EnumSet.allOf(DayOfWeek.class)), A320, LocalDate.of(2028, 2, 1), LocalDate.of(2028, 2, 29));

		assertThat(february2028).hasSize(29);
		assertThat(february2028.getLast().getFlightDate()).isEqualTo(LocalDate.of(2028, 2, 29));
	}

	@Test
	void departureAndArrivalAreUtcInstantsOnTheFlightDate() {
		FlightInstance first = FlightInstanceGenerator.generate(
				xy101(EnumSet.of(MONDAY)), A320, MONDAY_5_JAN_2026, MONDAY_5_JAN_2026).getFirst();

		assertThat(first.getDepartureAt()).isEqualTo(Instant.parse("2026-01-05T09:30:00Z"));
		assertThat(first.getArrivalAt()).isEqualTo(Instant.parse("2026-01-05T13:45:00Z"));
	}

	@Test
	void overnightFlightArrivesTheNextDay() {
		FlightSchedule overnight = FlightSchedule.create("XY202", "DXB", "SIN",
				LocalTime.parse("22:00"), LocalTime.parse("02:15"), 1L, EnumSet.of(MONDAY), Instant.EPOCH);

		FlightInstance first = FlightInstanceGenerator.generate(overnight, A320, MONDAY_5_JAN_2026, MONDAY_5_JAN_2026)
				.getFirst();

		assertThat(first.getDepartureAt()).isEqualTo(Instant.parse("2026-01-05T22:00:00Z"));
		assertThat(first.getArrivalAt()).isEqualTo(Instant.parse("2026-01-06T02:15:00Z"));
	}

	@Test
	void copiesFlightDetailsAndStartsWithEverySeatAvailable() {
		FlightInstance first = FlightInstanceGenerator.generate(
				xy101(EnumSet.of(MONDAY)), A320, MONDAY_5_JAN_2026, MONDAY_5_JAN_2026).getFirst();

		assertThat(first.getFlightNumber()).isEqualTo("XY101");
		assertThat(first.getOriginCode()).isEqualTo("DXB");
		assertThat(first.getDestinationCode()).isEqualTo("LHR");
		assertThat(first.getAircraftId()).isEqualTo(1L);
		assertThat(first.getTotalSeats()).isEqualTo(180);
		assertThat(first.getAvailableSeats()).isEqualTo(180);
	}

	@Test
	void anEmptyWindowGeneratesNothing() {
		assertThat(FlightInstanceGenerator.generate(
				xy101(EnumSet.allOf(DayOfWeek.class)), A320, ONE_YEAR_LATER, MONDAY_5_JAN_2026)).isEmpty();
	}

	private static FlightSchedule xy101(Set<DayOfWeek> days) {
		return FlightSchedule.create("XY101", "DXB", "LHR", LocalTime.parse("09:30"), LocalTime.parse("13:45"),
				1L, days, Instant.EPOCH);
	}

}
