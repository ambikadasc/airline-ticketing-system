package com.airline.reservation.schedule.service;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import com.airline.reservation.schedule.domain.FlightSchedule;

/**
 * A schedule as returned by the API. {@code generatedInstances} is only present in the
 * response to creation.
 */
public record ScheduleResult(
		Long id,
		String flightNumber,
		String origin,
		String destination,
		@JsonFormat(pattern = "HH:mm") LocalTime departureTime,
		@JsonFormat(pattern = "HH:mm") LocalTime arrivalTime,
		Long aircraftId,
		List<DayOfWeek> daysOfOperation,
		@JsonInclude(JsonInclude.Include.NON_NULL) Integer generatedInstances) {

	static ScheduleResult from(FlightSchedule schedule, Integer generatedInstances) {
		List<DayOfWeek> days = schedule.getDaysOfOperation().stream().sorted().toList(); // Monday first
		return new ScheduleResult(schedule.getId(), schedule.getFlightNumber(), schedule.getOriginCode(),
				schedule.getDestinationCode(), schedule.getDepartureTime(), schedule.getArrivalTime(),
				schedule.getAircraftId(), days, generatedInstances);
	}

}
