package com.airline.reservation.booking.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.airline.reservation.aircraft.AircraftRepository;
import com.airline.reservation.aircraft.SeatLayout;
import com.airline.reservation.booking.domain.Booking;
import com.airline.reservation.booking.domain.BookingStatus;
import com.airline.reservation.booking.domain.PassengerSeat;
import com.airline.reservation.booking.persistence.BookingRepository;
import com.airline.reservation.booking.service.policy.BookingPolicy;
import com.airline.reservation.common.config.AirlineProperties;
import com.airline.reservation.common.error.ApiException;
import com.airline.reservation.common.error.ErrorCode;
import com.airline.reservation.flight.domain.FlightInstance;
import com.airline.reservation.flight.persistence.FlightInstanceRepository;
import com.airline.reservation.flight.persistence.SeatOccupancyQueries;

/**
 * Booking use cases. Every write follows the same lock order: lock the flight row, release expired
 * holds on that flight, then read or change bookings. One lock per transaction, always taken first,
 * so writes on one flight run one at a time and no deadlock cycle can form.
 */
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
	private final BookingPolicy bookingPolicy;
	private final AirlineProperties properties;
	private final Clock clock;

	public BookingService(BookingRepository bookingRepository, FlightInstanceRepository instanceRepository,
			AircraftRepository aircraftRepository, SeatOccupancyQueries seatOccupancy,
			BookingRequestValidator validator, PnrGenerator pnrGenerator, BookingPolicy bookingPolicy,
			AirlineProperties properties, Clock clock) {
		this.bookingRepository = bookingRepository;
		this.instanceRepository = instanceRepository;
		this.aircraftRepository = aircraftRepository;
		this.seatOccupancy = seatOccupancy;
		this.validator = validator;
		this.pnrGenerator = pnrGenerator;
		this.bookingPolicy = bookingPolicy;
		this.properties = properties;
		this.clock = clock;
	}

	/** Books all requested seats or none. CONFIRMED by default; HELD when the seat hold is enabled. */
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
		expireHolds(flight, now);

		if (flight.isDepartedAt(now)) {
			throw new ApiException(ErrorCode.FLIGHT_NOT_BOOKABLE, "Flight has already departed: " + flight.getId());
		}
		// Instances may exist beyond the window if it was shortened; they are not bookable.
		LocalDate lastBookableDate = properties.lastBookableDate(LocalDate.now(clock));
		if (flight.getFlightDate().isAfter(lastBookableDate)) {
			throw new ApiException(ErrorCode.OUTSIDE_BOOKING_WINDOW,
					"Flights can be booked up to " + lastBookableDate + "; this flight is on " + flight.getFlightDate());
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

		Booking booking = bookingPolicy.newBooking(newReference(), flight.getId(), passengers, now);
		bookingRepository.saveAndFlush(booking);
		flight.reserve(passengers.size());

		log.info("Booking created: reference={} status={} flightInstanceId={} seats={}", booking.getReference(),
				booking.getStatus(), flight.getId(), requestedSeats);
		return BookingResult.from(booking, flight, now);
	}

	/** An expired hold is reported as EXPIRED even before a write has released it. */
	@Transactional(readOnly = true)
	public BookingResult getBooking(String reference) {
		Booking booking = bookingRepository.findWithSeatsByReference(reference)
				.orElseThrow(() -> new ApiException(ErrorCode.BOOKING_NOT_FOUND, "Booking not found: " + reference));
		FlightInstance flight = instanceRepository.findById(booking.getFlightInstanceId()).orElseThrow();
		return BookingResult.from(booking, flight, clock.instant());
	}

	/**
	 * Confirms a held booking while its hold is live. Confirming a CONFIRMED booking returns it
	 * unchanged, so the endpoint is safe to repeat (and harmless with the seat hold disabled).
	 */
	@Transactional
	public BookingResult confirmBooking(String reference) {
		Instant now = clock.instant();
		LockedBooking locked = lockFlightAndLoad(reference, now);
		Booking booking = locked.booking();

		switch (booking.getStatus()) {
			case CONFIRMED -> {
				return BookingResult.from(booking, locked.flight(), now);
			}
			case CANCELLED -> throw new ApiException(ErrorCode.BOOKING_NOT_CONFIRMABLE,
					"Booking " + reference + " is cancelled");
			case EXPIRED -> throw new ApiException(ErrorCode.HOLD_EXPIRED,
					"The hold on booking " + reference + " has expired");
			case HELD -> {
				// expireHolds has just run, so a HELD booking here is a live hold.
			}
		}
		if (locked.flight().isDepartedAt(now)) {
			throw new ApiException(ErrorCode.FLIGHT_NOT_BOOKABLE, "Flight has already departed: " + locked.flight().getId());
		}
		booking.confirm(now);
		log.info("Booking confirmed: reference={} flightInstanceId={}", reference, locked.flight().getId());
		return BookingResult.from(booking, locked.flight(), now);
	}

	/**
	 * Cancels the whole booking and releases its seats in one transaction. Idempotent: cancelling a
	 * booking that is already CANCELLED (or EXPIRED) returns it unchanged.
	 */
	@Transactional
	public BookingResult cancelBooking(String reference) {
		Instant now = clock.instant();
		LockedBooking locked = lockFlightAndLoad(reference, now);
		Booking booking = locked.booking();
		FlightInstance flight = locked.flight();

		if (booking.getStatus() == BookingStatus.CANCELLED || booking.getStatus() == BookingStatus.EXPIRED) {
			return BookingResult.from(booking, flight, now);
		}
		// The one cancellation rule: a booking can be cancelled until its flight departs.
		if (flight.isDepartedAt(now)) {
			throw new ApiException(ErrorCode.BOOKING_NOT_CANCELLABLE,
					"Booking " + reference + " cannot be cancelled: the flight has departed");
		}

		int released = booking.cancel(now);
		flight.release(released);
		log.info("Booking cancelled: reference={} flightInstanceId={} seatsReleased={}", reference, flight.getId(),
				released);
		return BookingResult.from(booking, flight, now);
	}

	private record LockedBooking(FlightInstance flight, Booking booking) {
	}

	/**
	 * The start of every write on an existing booking. Only the flight id is read first (no booking
	 * entity), then the flight is locked, then expired holds are released, and only then is the
	 * booking loaded. Loading it earlier would keep a stale copy: two simultaneous cancellations
	 * could both see it CONFIRMED and both give the seats back.
	 */
	private LockedBooking lockFlightAndLoad(String reference, Instant now) {
		Long flightId = bookingRepository.findFlightInstanceIdByReference(reference)
				.orElseThrow(() -> new ApiException(ErrorCode.BOOKING_NOT_FOUND, "Booking not found: " + reference));
		FlightInstance flight = instanceRepository.findByIdForUpdate(flightId).orElseThrow();
		expireHolds(flight, now);
		Booking booking = bookingRepository.findWithSeatsByReference(reference).orElseThrow();
		return new LockedBooking(flight, booking);
	}

	/**
	 * Lazy hold expiry, with no scheduler: holds on this flight that are past their expiry become
	 * EXPIRED and their seats go back to the flight. Runs under the flight lock, so it cannot race a
	 * confirmation or a new booking. Skipped when the seat hold is disabled: no hold can exist, so
	 * the default path pays nothing for the feature.
	 */
	private void expireHolds(FlightInstance flight, Instant now) {
		if (!properties.seatHold().enabled()) {
			return;
		}
		List<Booking> overdue = bookingRepository.findOverdueHolds(flight.getId(), now);
		if (overdue.isEmpty()) {
			return;
		}
		int released = 0;
		for (Booking booking : overdue) {
			released += booking.expireHold(now);
		}
		flight.release(released);
		// Write the changes now: the seat check that follows is plain SQL (SeatOccupancyQueries),
		// which does not trigger Hibernate's automatic flush.
		bookingRepository.flush();
		log.info("Holds expired: flightInstanceId={} bookings={} seatsReleased={}", flight.getId(), overdue.size(),
				released);
	}

	/** A new unused reference. The unique constraint on booking.reference is the final guard. */
	private String newReference() {
		for (int attempt = 0; attempt < MAX_REFERENCE_ATTEMPTS; attempt++) {
			String reference = pnrGenerator.next();
			if (!bookingRepository.existsByReference(reference)) {
				return reference;
			}
		}
		// Practically unreachable (it needs hundreds of millions of stored bookings); nothing was booked.
		throw new ApiException(ErrorCode.RETRY_LATER,
				"No free booking reference could be allocated; nothing was booked. Please retry");
	}

}
