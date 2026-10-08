package com.airline.reservation.booking.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;

/**
 * A booking: one flight, one or more seats, each with a passenger. The booking is the consistency
 * boundary for its seats: seats are created and changed only through it.
 * <p>
 * The flight is referenced by id, not by JPA association, so loading a booking never loads
 * (or locks) the flight.
 */
@Entity
public class Booking {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String reference;

	private Long flightInstanceId;

	@Enumerated(EnumType.STRING)
	private BookingStatus status;

	private int passengerCount;

	private Instant createdAt;

	private Instant cancelledAt;

	/** Set only for a booking created as HELD; kept afterwards as a record of the hold window. */
	private Instant holdExpiresAt;

	@OneToMany(mappedBy = "booking", cascade = CascadeType.ALL)
	@OrderBy("id")
	private List<BookingSeat> seats = new ArrayList<>();

	protected Booking() {
		// for JPA
	}

	/** A booking confirmed immediately: every seat is ACTIVE. */
	public static Booking confirmed(String reference, Long flightInstanceId, List<PassengerSeat> passengers,
			Instant now) {
		return create(reference, flightInstanceId, passengers, BookingStatus.CONFIRMED, SeatStatus.ACTIVE, now);
	}

	/** A booking whose seats are held until {@code holdExpiresAt}; it must be confirmed before then. */
	public static Booking held(String reference, Long flightInstanceId, List<PassengerSeat> passengers,
			Instant now, Instant holdExpiresAt) {
		Booking booking = create(reference, flightInstanceId, passengers, BookingStatus.HELD, SeatStatus.HELD, now);
		booking.holdExpiresAt = holdExpiresAt;
		return booking;
	}

	private static Booking create(String reference, Long flightInstanceId, List<PassengerSeat> passengers,
			BookingStatus status, SeatStatus seatStatus, Instant now) {
		if (passengers.isEmpty()) {
			throw new IllegalArgumentException("A booking needs at least one passenger");
		}
		Booking booking = new Booking();
		booking.reference = reference;
		booking.flightInstanceId = flightInstanceId;
		booking.status = status;
		booking.passengerCount = passengers.size();
		booking.createdAt = now;
		for (PassengerSeat passenger : passengers) {
			booking.seats.add(new BookingSeat(booking, passenger, seatStatus, now));
		}
		return booking;
	}

	/** Turns a live hold into a confirmed booking: the held seats become ACTIVE. */
	public void confirm(Instant now) {
		requireTransitionTo(BookingStatus.CONFIRMED);
		if (isHoldExpiredAt(now)) {
			throw new IllegalStateException("Hold on booking " + reference + " expired at " + holdExpiresAt);
		}
		seats.forEach(BookingSeat::activate);
		status = BookingStatus.CONFIRMED;
	}

	/** Ends an overdue hold: the seats are released. Returns how many, to give back to the flight. */
	public int expireHold(Instant now) {
		requireTransitionTo(BookingStatus.EXPIRED);
		if (!isHoldExpiredAt(now)) {
			throw new IllegalStateException("Hold on booking " + reference + " is live until " + holdExpiresAt);
		}
		int released = releaseSeats(now);
		status = BookingStatus.EXPIRED;
		return released;
	}

	/** True once a HELD booking has reached its expiry time (its seats are free from that instant). */
	public boolean isHoldExpiredAt(Instant now) {
		return status == BookingStatus.HELD && !holdExpiresAt.isAfter(now);
	}

	/**
	 * The status a reader should see now. Expired holds are released lazily (by the next write on
	 * the flight), so a HELD booking past its expiry already reads as EXPIRED.
	 */
	public BookingStatus effectiveStatus(Instant now) {
		return isHoldExpiredAt(now) ? BookingStatus.EXPIRED : status;
	}

	public Instant getHoldExpiresAt() {
		return holdExpiresAt;
	}

	/**
	 * Cancels the whole booking: every seat is released (so it can be booked again) and the
	 * booking keeps its history. Returns the number of seats released, which the caller gives
	 * back to the flight's available count.
	 */
	public int cancel(Instant now) {
		requireTransitionTo(BookingStatus.CANCELLED);
		int released = releaseSeats(now);
		status = BookingStatus.CANCELLED;
		cancelledAt = now;
		return released;
	}

	private void requireTransitionTo(BookingStatus target) {
		if (!status.canTransitionTo(target)) {
			throw new IllegalStateException("Booking " + reference + " cannot go from " + status + " to " + target);
		}
	}

	private int releaseSeats(Instant now) {
		int released = 0;
		for (BookingSeat seat : seats) {
			if (seat.release(now)) {
				released++;
			}
		}
		return released;
	}

	public Instant getCancelledAt() {
		return cancelledAt;
	}

	public Long getId() {
		return id;
	}

	public String getReference() {
		return reference;
	}

	public Long getFlightInstanceId() {
		return flightInstanceId;
	}

	public BookingStatus getStatus() {
		return status;
	}

	public int getPassengerCount() {
		return passengerCount;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public List<BookingSeat> getSeats() {
		return Collections.unmodifiableList(seats);
	}

}
