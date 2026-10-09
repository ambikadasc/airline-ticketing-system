package com.airline.reservation.flight.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.airline.reservation.airport.AirportRepository;
import com.airline.reservation.common.config.AirlineProperties;
import com.airline.reservation.common.error.ApiException;
import com.airline.reservation.common.error.ErrorCode;
import com.airline.reservation.flight.domain.FlightInstance;
import com.airline.reservation.flight.persistence.FlightInstanceRepository;
import com.airline.reservation.flight.persistence.SeatOccupancyQueries;

/**
 * Flight search by route and date. Schedules were matched to dates when instances were
 * generated, so search is a single indexed lookup with no day-of-week logic.
 */
@Service
public class FlightSearchService {

	private final FlightInstanceRepository instanceRepository;
	private final SeatOccupancyQueries seatOccupancy;
	private final AirportRepository airportRepository;
	private final AirlineProperties properties;
	private final Clock clock;

	public FlightSearchService(FlightInstanceRepository instanceRepository, SeatOccupancyQueries seatOccupancy,
			AirportRepository airportRepository, AirlineProperties properties, Clock clock) {
		this.instanceRepository = instanceRepository;
		this.seatOccupancy = seatOccupancy;
		this.airportRepository = airportRepository;
		this.properties = properties;
		this.clock = clock;
	}

	/** Flights on the route and date that have not departed yet, earliest first. */
	@Transactional(readOnly = true)
	public List<FlightSearchResult> search(String origin, String destination, LocalDate date) {
		if (origin.equals(destination)) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "origin and destination must differ");
		}
		requireInsideBookingWindow(date);
		requireAirport(origin);
		requireAirport(destination);

		Instant now = clock.instant();
		List<FlightInstance> flights = instanceRepository.search(origin, destination, date, now);
		// Seats on holds that expired but are not yet released count as free. With the seat hold
		// disabled no hold exists, so the query is skipped and the counter alone is the answer.
		Map<Long, Integer> freedByExpiredHolds = properties.seatHold().enabled()
				? seatOccupancy.seatsOnOverdueHolds(flights.stream().map(FlightInstance::getId).toList(), now)
				: Map.of();
		return flights.stream()
				.map(flight -> toResult(flight, flight.getAvailableSeats()
						+ freedByExpiredHolds.getOrDefault(flight.getId(), 0)))
				.toList();
	}

	private void requireInsideBookingWindow(LocalDate date) {
		LocalDate today = LocalDate.now(clock);
		LocalDate lastBookableDate = properties.lastBookableDate(today);
		if (date.isBefore(today) || date.isAfter(lastBookableDate)) {
			throw new ApiException(ErrorCode.OUTSIDE_BOOKING_WINDOW,
					"date must be between " + today + " and " + lastBookableDate);
		}
	}

	private void requireAirport(String code) {
		if (!airportRepository.existsById(code)) {
			throw new ApiException(ErrorCode.AIRPORT_NOT_FOUND, "Airport not found: " + code);
		}
	}

	private static FlightSearchResult toResult(FlightInstance flight, int availableSeats) {
		return new FlightSearchResult(flight.getId(), flight.getFlightNumber(), flight.getOriginCode(),
				flight.getDestinationCode(), flight.getFlightDate(), flight.getDepartureAt(), flight.getArrivalAt(),
				availableSeats);
	}

}
