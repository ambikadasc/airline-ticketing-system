package com.airline.reservation.schedule.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.airline.reservation.aircraft.SeatLayout;
import com.airline.reservation.flight.domain.FlightInstance;
import com.airline.reservation.schedule.domain.FlightSchedule;

/**
 * Turns a schedule into dated flight instances. Pure logic with no Spring or database
 * dependency, so it is unit-tested directly.
 */
public final class FlightInstanceGenerator {

	private FlightInstanceGenerator() {
	}

	/**
	 * One instance for every date in [from, to], both ends inclusive, whose UTC day of week is an
	 * operating day of the schedule. Returns an empty list when from is after to.
	 */
	public static List<FlightInstance> generate(FlightSchedule schedule, SeatLayout layout, LocalDate from,
			LocalDate to) {
		List<FlightInstance> instances = new ArrayList<>();
		for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
			if (schedule.operatesOn(date.getDayOfWeek())) {
				instances.add(instanceOn(date, schedule, layout));
			}
		}
		return instances;
	}

	private static FlightInstance instanceOn(LocalDate date, FlightSchedule schedule, SeatLayout layout) {
		Instant departureAt = date.atTime(schedule.getDepartureTime()).toInstant(ZoneOffset.UTC);
		Instant arrivalAt = date.plusDays(schedule.getArrivalDayOffset())
				.atTime(schedule.getArrivalTime())
				.toInstant(ZoneOffset.UTC);
		return FlightInstance.scheduled(schedule.getId(), schedule.getFlightNumber(), schedule.getOriginCode(),
				schedule.getDestinationCode(), schedule.getAircraftId(), date, departureAt, arrivalAt,
				layout.totalSeats());
	}

}
