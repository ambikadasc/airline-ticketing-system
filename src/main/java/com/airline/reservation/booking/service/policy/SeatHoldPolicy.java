package com.airline.reservation.booking.service.policy;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.airline.reservation.booking.domain.Booking;
import com.airline.reservation.booking.domain.PassengerSeat;

/** Optional: seats are held for {@code ttl}, and the booking must be confirmed before the hold expires. */
public class SeatHoldPolicy implements BookingPolicy {

	private final Duration ttl;

	public SeatHoldPolicy(Duration ttl) {
		this.ttl = ttl;
	}

	@Override
	public Booking newBooking(String reference, Long flightInstanceId, List<PassengerSeat> passengers, Instant now) {
		// Whole seconds: the expiry is shown to customers, and the database keeps only microseconds.
		Instant holdExpiresAt = now.plus(ttl).truncatedTo(ChronoUnit.SECONDS);
		return Booking.held(reference, flightInstanceId, passengers, now, holdExpiresAt);
	}

}
