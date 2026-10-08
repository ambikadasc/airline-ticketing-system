package com.airline.reservation.booking.domain;

import java.util.Set;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static com.airline.reservation.booking.domain.BookingStatus.CANCELLED;
import static com.airline.reservation.booking.domain.BookingStatus.EXPIRED;
import static com.airline.reservation.booking.domain.BookingStatus.HELD;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The booking state machine:
 * HELD -> CONFIRMED | EXPIRED | CANCELLED, CONFIRMED -> CANCELLED; CANCELLED and EXPIRED are terminal.
 */
class BookingStatusTest {

	private static final Set<String> ALLOWED = Set.of(
			"HELD>CONFIRMED", "HELD>EXPIRED", "HELD>CANCELLED", "CONFIRMED>CANCELLED");

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource({
			"HELD, CONFIRMED", "HELD, EXPIRED", "HELD, CANCELLED", "HELD, HELD",
			"CONFIRMED, CANCELLED", "CONFIRMED, HELD", "CONFIRMED, EXPIRED", "CONFIRMED, CONFIRMED",
			"CANCELLED, HELD", "CANCELLED, CONFIRMED", "CANCELLED, EXPIRED", "CANCELLED, CANCELLED",
			"EXPIRED, HELD", "EXPIRED, CONFIRMED", "EXPIRED, CANCELLED", "EXPIRED, EXPIRED"})
	void onlyTheDesignedTransitionsAreAllowed(BookingStatus from, BookingStatus to) {
		assertThat(from.canTransitionTo(to)).isEqualTo(ALLOWED.contains(from + ">" + to));
	}

	@ParameterizedTest
	@EnumSource(BookingStatus.class)
	void cancelledAndExpiredAreTerminal(BookingStatus target) {
		assertThat(CANCELLED.canTransitionTo(target)).isFalse();
		assertThat(EXPIRED.canTransitionTo(target)).isFalse();
	}

	@ParameterizedTest
	@EnumSource(BookingStatus.class)
	void nothingGoesBackToHeld(BookingStatus from) {
		assertThat(from.canTransitionTo(HELD)).isFalse();
	}

}
