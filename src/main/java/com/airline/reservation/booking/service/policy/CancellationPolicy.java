package com.airline.reservation.booking.service.policy;

import java.time.Instant;

import com.airline.reservation.booking.domain.Booking;
import com.airline.reservation.flight.domain.FlightInstance;

/**
 * Decides whether a booking may be cancelled now (the Strategy pattern). Called under the flight
 * lock, after the booking is loaded. A new cancellation rule (a cut-off before departure, an admin
 * override) is a new implementation; locking, transactions and the schema do not change.
 */
public interface CancellationPolicy {

	/** Returns normally if the booking may be cancelled, otherwise throws 409 BOOKING_NOT_CANCELLABLE. */
	void verifyCancellable(Booking booking, FlightInstance flight, Instant now);

}
