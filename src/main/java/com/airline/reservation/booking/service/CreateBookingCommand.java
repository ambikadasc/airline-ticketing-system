package com.airline.reservation.booking.service;

import java.util.List;

import com.airline.reservation.booking.domain.PassengerSeat;

/**
 * Input to {@link BookingService#createBooking}: shape-validated, seat numbers not yet normalised.
 * {@code idempotencyKey} is the optional {@code Idempotency-Key} header, null when absent.
 */
public record CreateBookingCommand(Long flightInstanceId, List<PassengerSeat> passengers, String idempotencyKey) {

	/** A request without an idempotency key. */
	public CreateBookingCommand(Long flightInstanceId, List<PassengerSeat> passengers) {
		this(flightInstanceId, passengers, null);
	}

}
