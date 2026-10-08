package com.airline.reservation.booking.domain;

/**
 * Booking status as a small state machine: each status lists where it may go next.
 * CONFIRMED -> CANCELLED; CANCELLED is terminal.
 */
public enum BookingStatus {
	CONFIRMED, CANCELLED;

	public boolean canTransitionTo(BookingStatus target) {
		return switch (this) {
			case CONFIRMED -> target == CANCELLED;
			case CANCELLED -> false;
		};
	}
}
