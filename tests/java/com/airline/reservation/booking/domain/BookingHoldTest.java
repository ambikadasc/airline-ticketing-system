package com.airline.reservation.booking.domain;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.tuple;

/** A seat hold: HELD until confirmed before it expires; otherwise EXPIRED and its seats released. */
class BookingHoldTest {

	private static final Instant NOW = Instant.parse("2026-01-05T00:00:00Z");
	private static final Instant EXPIRES = NOW.plusSeconds(600);

	@Test
	void aHeldBookingHoldsEverySeatUntilItExpires() {
		Booking booking = heldBooking();

		assertThat(booking.getStatus()).isEqualTo(BookingStatus.HELD);
		assertThat(booking.getHoldExpiresAt()).isEqualTo(EXPIRES);
		assertThat(booking.getSeats()).extracting(BookingSeat::getStatus).containsOnly(SeatStatus.HELD);
	}

	@Test
	void confirmingBeforeExpiryMakesTheSeatsActive() {
		Booking booking = heldBooking();

		booking.confirm(EXPIRES.minusSeconds(1));

		assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
		assertThat(booking.getSeats()).extracting(BookingSeat::getStatus).containsOnly(SeatStatus.ACTIVE);
	}

	@Test
	void anExpiredHoldCannotBeConfirmed() {
		Booking booking = heldBooking();

		assertThatIllegalStateException().isThrownBy(() -> booking.confirm(EXPIRES));
		assertThat(booking.getStatus()).isEqualTo(BookingStatus.HELD);
	}

	@Test
	void expiringAnOverdueHoldReleasesItsSeats() {
		Booking booking = heldBooking();

		int released = booking.expireHold(EXPIRES);

		assertThat(released).isEqualTo(2);
		assertThat(booking.getStatus()).isEqualTo(BookingStatus.EXPIRED);
		assertThat(booking.getSeats())
				.extracting(BookingSeat::getStatus, BookingSeat::getReleasedAt)
				.containsOnly(tuple(SeatStatus.RELEASED, EXPIRES));
	}

	@Test
	void aLiveHoldCannotBeExpired() {
		Booking booking = heldBooking();

		assertThatIllegalStateException().isThrownBy(() -> booking.expireHold(EXPIRES.minusSeconds(1)));
	}

	@Test
	void aHeldBookingCanBeCancelled() {
		Booking booking = heldBooking();

		int released = booking.cancel(NOW.plusSeconds(60));

		assertThat(released).isEqualTo(2);
		assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
		assertThat(booking.getSeats()).extracting(BookingSeat::getStatus).containsOnly(SeatStatus.RELEASED);
	}

	@Test
	void anOverdueHoldReadsAsExpiredBeforeAnyWriteReleasesIt() {
		Booking held = heldBooking();
		Booking confirmed = Booking.confirmed("CONF01", 42L, List.of(new PassengerSeat("Ayesha Khan", "12A")), NOW);

		assertThat(held.effectiveStatus(EXPIRES.minusSeconds(1))).isEqualTo(BookingStatus.HELD);
		assertThat(held.effectiveStatus(EXPIRES)).isEqualTo(BookingStatus.EXPIRED);
		assertThat(confirmed.effectiveStatus(EXPIRES)).isEqualTo(BookingStatus.CONFIRMED);
	}

	private static Booking heldBooking() {
		return Booking.held("HOLD01", 42L,
				List.of(new PassengerSeat("Ayesha Khan", "12A"), new PassengerSeat("Bilal Khan", "12B")), NOW, EXPIRES);
	}

}
