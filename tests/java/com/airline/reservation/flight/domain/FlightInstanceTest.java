package com.airline.reservation.flight.domain;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class FlightInstanceTest {

	private static final Instant DEPARTURE = Instant.parse("2026-01-05T09:30:00Z");

	@Test
	void reservingSeatsLowersTheAvailableCount() {
		FlightInstance flight = flightWithSeats(180);

		flight.reserve(2);

		assertThat(flight.getAvailableSeats()).isEqualTo(178);
	}

	@Test
	void cannotReserveMoreSeatsThanAreAvailable() {
		FlightInstance flight = flightWithSeats(2);

		assertThatIllegalStateException().isThrownBy(() -> flight.reserve(3));
		assertThat(flight.getAvailableSeats()).isEqualTo(2);
	}

	@Test
	void releasingSeatsRaisesTheAvailableCount() {
		FlightInstance flight = flightWithSeats(180);
		flight.reserve(5);

		flight.release(2);

		assertThat(flight.getAvailableSeats()).isEqualTo(177);
	}

	@Test
	void cannotReleaseMoreSeatsThanTheAircraftHas() {
		FlightInstance flight = flightWithSeats(180);
		flight.reserve(1);

		assertThatIllegalStateException().isThrownBy(() -> flight.release(2));
		assertThat(flight.getAvailableSeats()).isEqualTo(179);
	}

	@Test
	void hasDepartedAtAndAfterTheDepartureTime() {
		FlightInstance flight = flightWithSeats(180);

		assertThat(flight.isDepartedAt(DEPARTURE.minusSeconds(1))).isFalse();
		assertThat(flight.isDepartedAt(DEPARTURE)).isTrue();
		assertThat(flight.isDepartedAt(DEPARTURE.plusSeconds(1))).isTrue();
	}

	private static FlightInstance flightWithSeats(int seats) {
		return FlightInstance.scheduled(1L, "XY101", "DXB", "LHR", 1L, LocalDate.of(2026, 1, 5), DEPARTURE,
				Instant.parse("2026-01-05T13:45:00Z"), seats);
	}

}
