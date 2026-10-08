package com.airline.reservation.booking.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class BookingStatusTest {

	@Test
	void aConfirmedBookingCanBeCancelled() {
		assertThat(BookingStatus.CONFIRMED.canTransitionTo(BookingStatus.CANCELLED)).isTrue();
	}

	@Test
	void aStatusDoesNotTransitionToItself() {
		assertThat(BookingStatus.CONFIRMED.canTransitionTo(BookingStatus.CONFIRMED)).isFalse();
	}

	@ParameterizedTest
	@EnumSource(BookingStatus.class)
	void cancelledIsTerminal(BookingStatus target) {
		assertThat(BookingStatus.CANCELLED.canTransitionTo(target)).isFalse();
	}

}
