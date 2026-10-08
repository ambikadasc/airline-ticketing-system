package com.airline.reservation.booking.domain;

/** Status of one booked seat (a booking_seat row). Only ACTIVE seats count as taken. */
public enum SeatStatus {
	ACTIVE, RELEASED
}
