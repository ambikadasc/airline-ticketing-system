package com.airline.reservation.booking.domain;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.tuple;

class BookingTest {

	private static final Instant NOW = Instant.parse("2026-01-05T00:00:00Z");

	@Test
	void aConfirmedBookingHasOneActiveSeatPerPassenger() {
		Booking booking = Booking.confirmed("K7M2QX", 42L,
				List.of(new PassengerSeat("Ayesha Khan", "12A"), new PassengerSeat("Bilal Khan", "12B")), NOW);

		assertThat(booking.getReference()).isEqualTo("K7M2QX");
		assertThat(booking.getFlightInstanceId()).isEqualTo(42L);
		assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
		assertThat(booking.getPassengerCount()).isEqualTo(2);
		assertThat(booking.getCreatedAt()).isEqualTo(NOW);
		assertThat(booking.getSeats())
				.extracting(BookingSeat::getSeatNumber, BookingSeat::getPassengerName, BookingSeat::getStatus,
						BookingSeat::getFlightInstanceId, BookingSeat::getCreatedAt)
				.containsExactly(
						tuple("12A", "Ayesha Khan", SeatStatus.ACTIVE, 42L, NOW),
						tuple("12B", "Bilal Khan", SeatStatus.ACTIVE, 42L, NOW));
	}

	@Test
	void aBookingNeedsAtLeastOnePassenger() {
		assertThatIllegalArgumentException().isThrownBy(() -> Booking.confirmed("K7M2QX", 42L, List.of(), NOW));
	}

}
