package com.airline.reservation.flight.service;

import java.time.Instant;
import java.time.LocalDate;

/** One flight in a search result. Times are UTC instants. */
public record FlightSearchResult(
		Long flightInstanceId,
		String flightNumber,
		String origin,
		String destination,
		LocalDate flightDate,
		Instant departureTime,
		Instant arrivalTime,
		int availableSeats) {
}
