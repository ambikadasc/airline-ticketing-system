package com.airline.reservation.flight.domain;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * One bookable, dated flight generated from a schedule. Flight number, route and aircraft are
 * copied from the schedule (schedules never change) so search reads this table alone.
 * <p>
 * The schedule and aircraft are referenced by id, not by JPA association, so loading an
 * instance never pulls in other aggregates.
 */
@Entity
public class FlightInstance {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private Long scheduleId;

	private String flightNumber;

	private String originCode;

	private String destinationCode;

	private Long aircraftId;

	private LocalDate flightDate;

	private Instant departureAt;

	private Instant arrivalAt;

	private int totalSeats;

	private int availableSeats;

	protected FlightInstance() {
		// for JPA
	}

	private FlightInstance(Long scheduleId, String flightNumber, String originCode, String destinationCode,
			Long aircraftId, LocalDate flightDate, Instant departureAt, Instant arrivalAt, int totalSeats) {
		this.scheduleId = scheduleId;
		this.flightNumber = flightNumber;
		this.originCode = originCode;
		this.destinationCode = destinationCode;
		this.aircraftId = aircraftId;
		this.flightDate = flightDate;
		this.departureAt = departureAt;
		this.arrivalAt = arrivalAt;
		this.totalSeats = totalSeats;
		this.availableSeats = totalSeats;
	}

	/** A newly scheduled flight: every seat is available. */
	public static FlightInstance scheduled(Long scheduleId, String flightNumber, String originCode,
			String destinationCode, Long aircraftId, LocalDate flightDate, Instant departureAt, Instant arrivalAt,
			int totalSeats) {
		return new FlightInstance(scheduleId, flightNumber, originCode, destinationCode, aircraftId, flightDate,
				departureAt, arrivalAt, totalSeats);
	}

	/**
	 * Takes seats out of the available count. Called only while this row is locked, after the
	 * requested seats were checked free, so running short means a bug, not a user error.
	 */
	public void reserve(int seats) {
		if (seats > availableSeats) {
			throw new IllegalStateException(
					"Cannot reserve " + seats + " seats on flight " + id + ": only " + availableSeats + " available");
		}
		availableSeats -= seats;
	}

	/** Gives seats back to the available count, e.g. after a cancellation. Called under the row lock. */
	public void release(int seats) {
		if (availableSeats + seats > totalSeats) {
			throw new IllegalStateException(
					"Cannot release " + seats + " seats on flight " + id + ": only " + (totalSeats - availableSeats) + " taken");
		}
		availableSeats += seats;
	}

	/** A flight can be booked only before it departs. */
	public boolean isDepartedAt(Instant now) {
		return !departureAt.isAfter(now);
	}

	public Long getId() {
		return id;
	}

	public Long getScheduleId() {
		return scheduleId;
	}

	public String getFlightNumber() {
		return flightNumber;
	}

	public String getOriginCode() {
		return originCode;
	}

	public String getDestinationCode() {
		return destinationCode;
	}

	public Long getAircraftId() {
		return aircraftId;
	}

	public LocalDate getFlightDate() {
		return flightDate;
	}

	public Instant getDepartureAt() {
		return departureAt;
	}

	public Instant getArrivalAt() {
		return arrivalAt;
	}

	public int getTotalSeats() {
		return totalSeats;
	}

	public int getAvailableSeats() {
		return availableSeats;
	}

}
