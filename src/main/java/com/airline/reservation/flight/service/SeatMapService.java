package com.airline.reservation.flight.service;

import java.time.Clock;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.airline.reservation.aircraft.Aircraft;
import com.airline.reservation.aircraft.AircraftRepository;
import com.airline.reservation.common.error.ApiException;
import com.airline.reservation.common.error.ErrorCode;
import com.airline.reservation.flight.domain.FlightInstance;
import com.airline.reservation.flight.persistence.FlightInstanceRepository;
import com.airline.reservation.flight.persistence.SeatOccupancyQueries;
import com.airline.reservation.flight.service.SeatMapResult.SeatAvailability;
import com.airline.reservation.flight.service.SeatMapResult.SeatResult;

/**
 * The seat map: the aircraft's seat layout minus the seats taken on this flight. Seats are not
 * stored per flight; only taken seats have rows. No lock: the map shows everything committed at
 * the moment of the query.
 */
@Service
public class SeatMapService {

	private final FlightInstanceRepository instanceRepository;
	private final AircraftRepository aircraftRepository;
	private final SeatOccupancyQueries seatOccupancy;
	private final Clock clock;

	public SeatMapService(FlightInstanceRepository instanceRepository, AircraftRepository aircraftRepository,
			SeatOccupancyQueries seatOccupancy, Clock clock) {
		this.instanceRepository = instanceRepository;
		this.aircraftRepository = aircraftRepository;
		this.seatOccupancy = seatOccupancy;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public SeatMapResult getSeatMap(Long flightInstanceId) {
		FlightInstance flight = instanceRepository.findById(flightInstanceId)
				.orElseThrow(() -> new ApiException(ErrorCode.FLIGHT_NOT_FOUND, "Flight not found: " + flightInstanceId));
		Aircraft aircraft = aircraftRepository.findById(flight.getAircraftId()).orElseThrow();
		Set<String> taken = seatOccupancy.takenSeats(flight.getId(), clock.instant());

		List<SeatResult> seats = aircraft.seatLayout().seats().stream()
				.map(seat -> new SeatResult(seat, taken.contains(seat) ? SeatAvailability.BOOKED : SeatAvailability.AVAILABLE))
				.toList();
		return new SeatMapResult(flight.getId(), flight.getFlightNumber(), flight.getFlightDate(),
				aircraft.getAircraftType(), seats.size(), seats.size() - taken.size(), seats);
	}

}
