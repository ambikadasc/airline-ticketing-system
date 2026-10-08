package com.airline.reservation.booking.service.policy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.airline.reservation.booking.domain.Booking;
import com.airline.reservation.booking.domain.BookingStatus;
import com.airline.reservation.booking.domain.PassengerSeat;

import static org.assertj.core.api.Assertions.assertThat;

class BookingPolicyTest {

	private static final Instant NOW = Instant.parse("2026-01-05T00:00:00Z");
	private static final List<PassengerSeat> PASSENGERS = List.of(new PassengerSeat("Ayesha Khan", "12A"));

	@Test
	void immediateConfirmationCreatesAConfirmedBooking() {
		Booking booking = new ImmediateConfirmationPolicy().newBooking("K7M2QX", 42L, PASSENGERS, NOW);

		assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
		assertThat(booking.getHoldExpiresAt()).isNull();
	}

	@Test
	void seatHoldCreatesAHeldBookingThatExpiresAfterTheTtl() {
		Booking booking = new SeatHoldPolicy(Duration.ofMinutes(10)).newBooking("K7M2QX", 42L, PASSENGERS, NOW);

		assertThat(booking.getStatus()).isEqualTo(BookingStatus.HELD);
		assertThat(booking.getHoldExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
	}

}
