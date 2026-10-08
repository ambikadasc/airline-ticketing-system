package com.airline.reservation.booking.api;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.airline.reservation.booking.domain.PassengerSeat;
import com.airline.reservation.booking.service.CreateBookingCommand;

/**
 * Body of {@code POST /api/v1/bookings}. Shape only; the maximum number of passengers, seat format
 * and duplicates are checked by the booking validator, and seat availability under the flight lock.
 */
public record CreateBookingRequest(
		@Schema(example = "1")
		@NotNull @Positive
		Long flightInstanceId,

		@NotEmpty @Valid
		List<PassengerRequest> passengers) {

	public record PassengerRequest(
			@Schema(example = "Ayesha Khan")
			@NotBlank @Size(max = 120)
			String name,

			@Schema(example = "12A")
			@NotBlank
			String seatNumber) {
	}

	public CreateBookingCommand toCommand() {
		List<PassengerSeat> seats = passengers.stream()
				.map(passenger -> new PassengerSeat(passenger.name(), passenger.seatNumber()))
				.toList();
		return new CreateBookingCommand(flightInstanceId, seats);
	}

}
