package com.airline.reservation.booking.service.policy;

import java.time.Instant;

import org.springframework.stereotype.Component;

import com.airline.reservation.booking.domain.Booking;
import com.airline.reservation.common.error.ApiException;
import com.airline.reservation.common.error.ErrorCode;
import com.airline.reservation.flight.domain.FlightInstance;

/** Today's only cancellation rule: a booking can be cancelled until its flight departs. */
@Component
public class BeforeDepartureCancellationPolicy implements CancellationPolicy {

	@Override
	public void verifyCancellable(Booking booking, FlightInstance flight, Instant now) {
		if (flight.isDepartedAt(now)) {
			throw new ApiException(ErrorCode.BOOKING_NOT_CANCELLABLE,
					"Booking " + booking.getReference() + " cannot be cancelled: the flight has departed");
		}
	}

}
