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

	@OneToMany(mappedBy = "booking", cascade = CascadeType.ALL)
	@OrderBy("id")
	private List<BookingSeat> seats = new ArrayList<>();

	protected Booking() {
		// for JPA
	}

	/** A booking confirmed immediately: every seat is ACTIVE. */
	public static Booking confirmed(String reference, Long flightInstanceId, List<PassengerSeat> passengers,
			Instant now) {
		if (passengers.isEmpty()) {
			throw new IllegalArgumentException("A booking needs at least one passenger");
		}
		Booking booking = new Booking();
		booking.reference = reference;
		booking.flightInstanceId = flightInstanceId;
		booking.status = BookingStatus.CONFIRMED;
		booking.passengerCount = passengers.size();
		booking.createdAt = now;
		for (PassengerSeat passenger : passengers) {
			booking.seats.add(new BookingSeat(booking, passenger, SeatStatus.ACTIVE, now));
		}
		return booking;
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
