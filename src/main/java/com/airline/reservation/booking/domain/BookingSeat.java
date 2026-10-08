package com.airline.reservation.booking.domain;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

/**
 * One seat of a booking with its passenger. Carries the flight id as well as the booking: the
 * partial unique index uq_active_seat (flight_instance_id, seat_number) needs both on this table.
 * Created and changed only through {@link Booking}.
 */
@Entity
public class BookingSeat {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "booking_id")
	private Booking booking;

	private Long flightInstanceId;

	private String seatNumber;

	private String passengerName;

	@Enumerated(EnumType.STRING)
	private SeatStatus status;

	private Instant createdAt;

	private Instant releasedAt;

	protected BookingSeat() {
		// for JPA
	}

	BookingSeat(Booking booking, PassengerSeat passenger, SeatStatus status, Instant now) {
		this.booking = booking;
		this.flightInstanceId = booking.getFlightInstanceId();
		this.seatNumber = passenger.seatNumber();
		this.passengerName = passenger.name();
		this.status = status;
		this.createdAt = now;
	}

	/** Frees the seat for other bookings. Returns false if it was already released. */
	boolean release(Instant now) {
		if (status == SeatStatus.RELEASED) {
			return false;
		}
		status = SeatStatus.RELEASED;
		releasedAt = now;
		return true;
	}

	public String getSeatNumber() {
		return seatNumber;
	}

	public String getPassengerName() {
		return passengerName;
	}

	public SeatStatus getStatus() {
		return status;
	}

	public Long getFlightInstanceId() {
		return flightInstanceId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getReleasedAt() {
		return releasedAt;
	}

}
