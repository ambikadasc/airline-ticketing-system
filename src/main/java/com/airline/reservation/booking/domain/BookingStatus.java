package com.airline.reservation.booking.domain;

/**
 * Booking status as a small state machine: each status lists where it may go next.
 * <pre>
 * HELD      -> CONFIRMED | EXPIRED | CANCELLED   (HELD exists only with the seat hold enabled)
 * CONFIRMED -> CANCELLED
 * CANCELLED, EXPIRED: terminal
 * </pre>
 */
public enum BookingStatus {
	HELD, CONFIRMED, CANCELLED, EXPIRED;

	public boolean canTransitionTo(BookingStatus target) {
		return switch (this) {
			case HELD -> target == CONFIRMED || target == EXPIRED || target == CANCELLED;
			case CONFIRMED -> target == CANCELLED;
			case CANCELLED, EXPIRED -> false;
		};
	}
}
