package com.airline.reservation.flight.service;

import java.time.LocalDate;
import java.util.List;

/** The seat map of one flight: every seat of the aircraft, in layout order, with its availability. */
public record SeatMapResult(
		Long flightInstanceId,
		String flightNumber,
		LocalDate flightDate,
		String aircraftType,
		int totalSeats,
		int availableSeats,
		List<SeatResult> seats) {

	public record SeatResult(String seatNumber, SeatAvailability status) {
	}

	/** The two seat states the brief defines. A seat on a live hold shows as BOOKED. */
	public enum SeatAvailability {
		AVAILABLE, BOOKED
	}

}
