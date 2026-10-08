package com.airline.reservation.booking.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.airline.reservation.aircraft.AircraftRepository;
import com.airline.reservation.aircraft.SeatLayout;
import com.airline.reservation.booking.domain.Booking;
import com.airline.reservation.booking.domain.PassengerSeat;
import com.airline.reservation.booking.persistence.BookingRepository;
import com.airline.reservation.common.error.ApiException;
import com.airline.reservation.common.error.ErrorCode;
import com.airline.reservation.flight.domain.FlightInstance;
import com.airline.reservation.flight.persistence.FlightInstanceRepository;
import com.airline.reservation.flight.persistence.SeatOccupancyQueries;

@Service
public class BookingService {

	private static final Logger log = LoggerFactory.getLogger(BookingService.class);
	private static final int MAX_REFERENCE_ATTEMPTS = 5;

	private final BookingRepository bookingRepository;
	private final FlightInstanceRepository instanceRepository;
	private final AircraftRepository aircraftRepository;
	private final SeatOccupancyQueries seatOccupancy;
	private final BookingRequestValidator validator;
	private final PnrGenerator pnrGenerator;
	private final Clock clock;

	public BookingService(BookingRepository bookingRepository, FlightInstanceRepository instanceRepository,
			AircraftRepository aircraftRepository, SeatOccupancyQueries seatOccupancy,
			BookingRequestValidator validator, PnrGenerator pnrGenerator, Clock clock) {
		this.bookingRepository = bookingRepository;
		this.instanceRepository = instanceRepository;
		this.aircraftRepository = aircraftRepository;
		this.seatOccupancy = seatOccupancy;
		this.validator = validator;
		this.pnrGenerator = pnrGenerator;
		this.clock = clock;
	}

	@Transactional
	public BookingResult createBooking(CreateBookingCommand command) {
		List<PassengerSeat> passengers = validator.validate(command);
		List<String> requestedSeats = passengers.stream().map(PassengerSeat::seatNumber).toList();

		// Lock the flight row first: every write on this flight now waits for us, so the seat check
		// below and the inserts that follow cannot interleave with another booking or cancellation.
		FlightInstance flight = instanceRepository.findByIdForUpdate(command.flightInstanceId())
				.orElseThrow(() -> new ApiException(ErrorCode.FLIGHT_NOT_FOUND,
						"Flight not found: " + command.flightInstanceId()));

		Instant now = clock.instant();
		if (flight.isDepartedAt(now)) {
			throw new ApiException(ErrorCode.FLIGHT_NOT_BOOKABLE, "Flight has already departed: " + flight.getId());
		}

		SeatLayout layout = aircraftRepository.findById(flight.getAircraftId()).orElseThrow().seatLayout();
		List<String> invalidSeats = requestedSeats.stream().filter(seat -> !layout.contains(seat)).toList();
		if (!invalidSeats.isEmpty()) {
			throw new InvalidSeatException(invalidSeats);
		}

		Set<String> taken = seatOccupancy.takenSeats(flight.getId(), now);
		List<String> unavailableSeats = requestedSeats.stream().filter(taken::contains).toList();
		if (!unavailableSeats.isEmpty()) {
			log.info("Seat conflict: flightInstanceId={} seats={}", flight.getId(), unavailableSeats);
			throw new SeatUnavailableException(unavailableSeats);
		}

		Booking booking = Booking.confirmed(newReference(), flight.getId(), passengers, now);
		bookingRepository.saveAndFlush(booking);
		flight.reserve(passengers.size());

		log.info("Booking created: reference={} flightInstanceId={} seats={}", booking.getReference(),
				flight.getId(), requestedSeats);
		return BookingResult.from(booking, flight);
	}

	@Transactional(readOnly = true)
	public BookingResult getBooking(String reference) {
		Booking booking = bookingRepository.findWithSeatsByReference(reference)
				.orElseThrow(() -> new ApiException(ErrorCode.BOOKING_NOT_FOUND, "Booking not found: " + reference));
		FlightInstance flight = instanceRepository.findById(booking.getFlightInstanceId()).orElseThrow();
		return BookingResult.from(booking, flight);
	}

	/** A new unused reference. The unique constraint on booking.reference is the final guard. */
	private String newReference() {
		for (int attempt = 0; attempt < MAX_REFERENCE_ATTEMPTS; attempt++) {
			String reference = pnrGenerator.next();
			if (!bookingRepository.existsByReference(reference)) {
				return reference;
			}
		}
		throw new IllegalStateException("No unused booking reference after " + MAX_REFERENCE_ATTEMPTS + " attempts");
	}

}
