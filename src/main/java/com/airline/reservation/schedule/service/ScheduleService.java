package com.airline.reservation.schedule.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.airline.reservation.aircraft.Aircraft;
import com.airline.reservation.aircraft.AircraftRepository;
import com.airline.reservation.aircraft.SeatLayout;
import com.airline.reservation.airport.AirportRepository;
import com.airline.reservation.common.config.AirlineProperties;
import com.airline.reservation.common.error.ApiException;
import com.airline.reservation.common.error.ErrorCode;
import com.airline.reservation.flight.domain.FlightInstance;
import com.airline.reservation.flight.persistence.FlightInstanceBulkWriter;
import com.airline.reservation.schedule.domain.FlightSchedule;
import com.airline.reservation.schedule.persistence.FlightScheduleRepository;

/**
 * Back-office use cases for schedules, and the rolling generation of flight instances from them.
 */
@Service
public class ScheduleService {

	private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);

	private final FlightScheduleRepository scheduleRepository;
	private final AirportRepository airportRepository;
	private final AircraftRepository aircraftRepository;
	private final FlightInstanceBulkWriter instanceWriter;
	private final AirlineProperties properties;
	private final Clock clock;

	public ScheduleService(FlightScheduleRepository scheduleRepository, AirportRepository airportRepository,
			AircraftRepository aircraftRepository, FlightInstanceBulkWriter instanceWriter,
			AirlineProperties properties, Clock clock) {
		this.scheduleRepository = scheduleRepository;
		this.airportRepository = airportRepository;
		this.aircraftRepository = aircraftRepository;
		this.instanceWriter = instanceWriter;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * Creates the schedule and, in the same transaction, its flight instances for the booking window.
	 */
	@Transactional
	public ScheduleResult createSchedule(CreateScheduleCommand command) {
		requireAirport(command.origin());
		requireAirport(command.destination());
		Aircraft aircraft = aircraftRepository.findById(command.aircraftId())
				.orElseThrow(() -> new ApiException(ErrorCode.AIRCRAFT_NOT_FOUND,
						"Aircraft not found: " + command.aircraftId()));
		if (scheduleRepository.existsByFlightNumber(command.flightNumber())) {
			throw new ApiException(ErrorCode.DUPLICATE_FLIGHT_NUMBER,
					"A schedule with flight number " + command.flightNumber() + " already exists");
		}

		FlightSchedule schedule = FlightSchedule.create(command.flightNumber(), command.origin(),
				command.destination(), command.departureTime(), command.arrivalTime(), command.aircraftId(),
				command.daysOfOperation(), clock.instant());
		// Flush now: the JDBC instance insert below needs the schedule row and its generated id.
		scheduleRepository.saveAndFlush(schedule);

		int generated = generateWindow(schedule, aircraft.seatLayout());
		log.info("Schedule created: id={} flightNumber={} instances={}", schedule.getId(),
				schedule.getFlightNumber(), generated);
		return ScheduleResult.from(schedule, generated);
	}

	@Transactional(readOnly = true)
	public ScheduleResult getSchedule(Long id) {
		FlightSchedule schedule = scheduleRepository.findById(id)
				.orElseThrow(() -> new ApiException(ErrorCode.SCHEDULE_NOT_FOUND, "Schedule not found: " + id));
		return ScheduleResult.from(schedule, null);
	}

	/**
	 * Tops up every schedule's instances so the window always reaches today + booking window.
	 * Idempotent: dates that already have an instance are skipped, so it is safe to run daily,
	 * at startup, after downtime, or on several nodes at once. Triggered by the daily job, at startup,
	 * and on demand through the admin API.
	 *
	 * @return how many instances were inserted, and the last date the window now reaches
	 */
	@Transactional
	public InstanceWindowResult extendInstanceWindow() {
		Map<Long, SeatLayout> layouts = new HashMap<>();
		int inserted = 0;
		for (FlightSchedule schedule : scheduleRepository.findAllWithDays()) {
			SeatLayout layout = layouts.computeIfAbsent(schedule.getAircraftId(),
					id -> aircraftRepository.findById(id).orElseThrow().seatLayout());
			inserted += generateWindow(schedule, layout);
		}
		LocalDate windowEnd = properties.lastBookableDate(LocalDate.now(clock));
		log.info("Instance window extended: inserted={} windowEnd={}", inserted, windowEnd);
		return new InstanceWindowResult(inserted, windowEnd);
	}

	private int generateWindow(FlightSchedule schedule, SeatLayout layout) {
		LocalDate today = LocalDate.now(clock);
		List<FlightInstance> instances = FlightInstanceGenerator.generate(schedule, layout, today,
				properties.lastBookableDate(today));
		return instanceWriter.insertMissing(instances);
	}

	private void requireAirport(String code) {
		if (!airportRepository.existsById(code)) {
			throw new ApiException(ErrorCode.AIRPORT_NOT_FOUND, "Airport not found: " + code);
		}
	}

}
