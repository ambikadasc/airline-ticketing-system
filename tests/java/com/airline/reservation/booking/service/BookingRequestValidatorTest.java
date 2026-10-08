package com.airline.reservation.booking.service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.airline.reservation.booking.domain.PassengerSeat;
import com.airline.reservation.common.config.AirlineProperties;
import com.airline.reservation.common.error.ApiException;
import com.airline.reservation.common.error.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingRequestValidatorTest {

	private final BookingRequestValidator validator = new BookingRequestValidator(
			new AirlineProperties(365, 9, "0 5 0 * * *", Duration.ofSeconds(1),
					new AirlineProperties.SeatHold(false, Duration.ofMinutes(10))));

	@Test
	void normalisesSeatNumbersToTrimmedUpperCase() {
		List<PassengerSeat> passengers = validator.validate(command(seat(" 12a "), seat("3F")));

		assertThat(passengers).extracting(PassengerSeat::seatNumber).containsExactly("12A", "3F");
		assertThat(passengers).extracting(PassengerSeat::name).containsExactly("Passenger 12a", "Passenger 3F");
	}

	@Test
	void acceptsUpToTheMaximumNumberOfPassengers() {
		List<PassengerSeat> nine = List.of(seat("1A"), seat("1B"), seat("1C"), seat("1D"), seat("1E"), seat("1F"),
				seat("2A"), seat("2B"), seat("2C"));

		assertThat(validator.validate(new CreateBookingCommand(1L, nine))).hasSize(9);
	}

	@Test
	void rejectsNoPassengers() {
		assertInvalid(new CreateBookingCommand(1L, Collections.emptyList()));
	}

	@Test
	void rejectsMoreThanTheMaximumNumberOfPassengers() {
		List<PassengerSeat> ten = List.of(seat("1A"), seat("1B"), seat("1C"), seat("1D"), seat("1E"), seat("1F"),
				seat("2A"), seat("2B"), seat("2C"), seat("2D"));

		assertInvalid(new CreateBookingCommand(1L, ten));
	}

	@ParameterizedTest
	@ValueSource(strings = {"A12", "100A", "12", "0A", "12AB", "1-A"})
	void rejectsMalformedSeatNumbers(String seatNumber) {
		assertInvalid(command(seat(seatNumber)));
	}

	@Test
	void rejectsTheSameSeatTwiceEvenInDifferentCase() {
		assertInvalid(command(seat("12A"), seat("12a")));
	}

	private void assertInvalid(CreateBookingCommand command) {
		assertThatThrownBy(() -> validator.validate(command))
				.isInstanceOf(ApiException.class)
				.extracting(ex -> ((ApiException) ex).getCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR);
	}

	private static CreateBookingCommand command(PassengerSeat... passengers) {
		return new CreateBookingCommand(1L, List.of(passengers));
	}

	private static PassengerSeat seat(String seatNumber) {
		return new PassengerSeat("Passenger " + seatNumber.trim(), seatNumber);
	}

}
