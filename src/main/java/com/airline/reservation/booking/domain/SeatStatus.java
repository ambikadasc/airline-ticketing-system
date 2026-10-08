package com.airline.reservation.booking.domain;

/**
 * Status of one booked seat (a booking_seat row). ACTIVE and HELD seats are taken (a HELD seat only
 * until its hold expires); RELEASED seats are free again.
 */
public enum SeatStatus {
	HELD, ACTIVE, RELEASED
}
