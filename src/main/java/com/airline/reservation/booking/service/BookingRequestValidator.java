package com.airline.reservation.booking.service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.airline.reservation.booking.domain.PassengerSeat;
import com.airline.reservation.common.config.AirlineProperties;
import com.airline.reservation.common.error.ApiException;
import com.airline.reservation.common.error.ErrorCode;

/**
 * Booking rules that need no database: passenger count, seat-number format, no duplicates.
 * One private method per rule. Rules that need the flight (seat exists on the aircraft, seat free,
 * flight not departed) are checked by the service under the flight lock.
 * <p>
 * If the rules grow, the refactor path is a list of rule objects behind one interface.
 */
@Component
public class BookingRequestValidator {

	/** Row 1-99 without a leading zero, then one letter. */
	private static final Pattern SEAT_NUMBER = Pattern.compile("^[1-9]\\d?[A-Z]$");

	private final AirlineProperties properties;

	public BookingRequestValidator(AirlineProperties properties) {
		this.properties = properties;
	}

	/** Returns the passengers with seat numbers trimmed and upper-cased. */
	public List<PassengerSeat> validate(CreateBookingCommand command) {
		validatePassengerCount(command.passengers());
		List<PassengerSeat> passengers = normaliseSeatNumbers(command.passengers());
		validateSeatFormat(passengers);
		rejectDuplicateSeats(passengers);
		return passengers;
	}

	private void validatePassengerCount(List<PassengerSeat> passengers) {
		int max = properties.maxSeatsPerBooking();
		if (passengers.isEmpty() || passengers.size() > max) {
			throw invalid("A booking must have between 1 and " + max + " passengers");
		}
	}

	private List<PassengerSeat> normaliseSeatNumbers(List<PassengerSeat> passengers) {
		return passengers.stream()
				.map(p -> new PassengerSeat(p.name(), p.seatNumber().trim().toUpperCase(Locale.ROOT)))
				.toList();
	}

	private void validateSeatFormat(List<PassengerSeat> passengers) {
		for (PassengerSeat passenger : passengers) {
			if (!SEAT_NUMBER.matcher(passenger.seatNumber()).matches()) {
				throw invalid("Seat number must be a row and a letter, e.g. 12A: " + passenger.seatNumber());
			}
		}
	}

	private void rejectDuplicateSeats(List<PassengerSeat> passengers) {
		Set<String> seen = new HashSet<>();
		for (PassengerSeat passenger : passengers) {
			if (!seen.add(passenger.seatNumber())) {
				throw invalid("Seat requested more than once: " + passenger.seatNumber());
			}
		}
	}

	private static ApiException invalid(String detail) {
		return new ApiException(ErrorCode.VALIDATION_ERROR, detail);
	}

}
