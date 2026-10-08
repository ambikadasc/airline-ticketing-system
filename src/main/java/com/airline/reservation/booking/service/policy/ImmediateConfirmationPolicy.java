package com.airline.reservation.booking.service.policy;

import java.time.Instant;
import java.util.List;

import com.airline.reservation.booking.domain.Booking;
import com.airline.reservation.booking.domain.PassengerSeat;

/** The default, as the brief describes: a booking is confirmed the moment it is made. */
public class ImmediateConfirmationPolicy implements BookingPolicy {

	@Override
	public Booking newBooking(String reference, Long flightInstanceId, List<PassengerSeat> passengers, Instant now) {
		return Booking.confirmed(reference, flightInstanceId, passengers, now);
	}

}
