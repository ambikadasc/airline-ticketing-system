package com.airline.reservation.booking.service.policy;

import java.time.Instant;
import java.util.List;

import com.airline.reservation.booking.domain.Booking;
import com.airline.reservation.booking.domain.PassengerSeat;

/**
 * How a new booking starts (the Strategy pattern): confirmed at once, or held until the customer
 * confirms. Chosen once, from configuration, in {@link BookingPolicyConfig}.
 */
public interface BookingPolicy {

	Booking newBooking(String reference, Long flightInstanceId, List<PassengerSeat> passengers, Instant now);

}
