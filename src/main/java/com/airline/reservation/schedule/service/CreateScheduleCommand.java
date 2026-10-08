package com.airline.reservation.schedule.service;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Set;

/** Input to {@link ScheduleService#createSchedule}: an already shape-validated request. */
public record CreateScheduleCommand(
		String flightNumber,
		String origin,
		String destination,
		LocalTime departureTime,
		LocalTime arrivalTime,
		Long aircraftId,
		Set<DayOfWeek> daysOfOperation) {
}
