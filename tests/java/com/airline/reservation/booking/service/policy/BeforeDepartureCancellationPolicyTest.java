package com.airline.reservation.booking.service.policy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.airline.reservation.booking.domain.Booking;
import com.airline.reservation.booking.domain.PassengerSeat;
import com.airline.reservation.common.error.ApiException;
import com.airline.reservation.common.error.ErrorCode;
import com.airline.reservation.flight.domain.FlightInstance;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BeforeDepartureCancellationPolicyTest {

	private static final Instant DEPARTURE = Instant.parse("2026-01-06T09:30:00Z");

	private final CancellationPolicy policy = new BeforeDepartureCancellationPolicy();

	private final FlightInstance flight = FlightInstance.scheduled(1L, "XY101", "DXB", "LHR", 1L,
			LocalDate.of(2026, 1, 6), DEPARTURE, Instant.parse("2026-01-06T13:45:00Z"), 180);

	private final Booking booking = Booking.confirmed("K7M2QX", 1L, List.of(new PassengerSeat("Ayesha Khan", "12A")),
			Instant.parse("2026-01-05T00:00:00Z"));

	@Test
	void allowsCancellationBeforeDeparture() {
		assertThatCode(() -> policy.verifyCancellable(booking, flight, DEPARTURE.minusSeconds(1)))
				.doesNotThrowAnyException();
	}

	@Test
	void refusesCancellationOnceTheFlightHasDeparted() {
		assertThatThrownBy(() -> policy.verifyCancellable(booking, flight, DEPARTURE))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).getCode())
				.isEqualTo(ErrorCode.BOOKING_NOT_CANCELLABLE);
	}

}
