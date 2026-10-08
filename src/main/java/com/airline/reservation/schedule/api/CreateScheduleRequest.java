package com.airline.reservation.schedule.api;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Set;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import com.airline.reservation.schedule.service.CreateScheduleCommand;

/**
 * Body of {@code POST /api/v1/admin/schedules}. Times are UTC; an arrival at or before the
 * departure time means the flight lands the next day.
 */
public record CreateScheduleRequest(
		@Schema(example = "XY101")
		@NotBlank @Pattern(regexp = "^[A-Z0-9]{2}\\d{1,4}$", message = "must be a 2-character airline code followed by 1-4 digits")
		String flightNumber,

		@Schema(example = "DXB")
		@NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter IATA code")
		String origin,

		@Schema(example = "LHR")
		@NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter IATA code")
		String destination,

		@Schema(type = "string", example = "09:30", description = "UTC, HH:mm")
		@NotNull
		LocalTime departureTime,

		@Schema(type = "string", example = "13:45", description = "UTC, HH:mm")
		@NotNull
		LocalTime arrivalTime,

		@Schema(example = "1")
		@NotNull @Positive
		Long aircraftId,

		@NotEmpty
		Set<DayOfWeek> daysOfOperation) {

	@Schema(hidden = true)
	@AssertTrue(message = "origin and destination must differ")
	public boolean isRouteDistinct() {
		return origin == null || !origin.equals(destination);
	}

	public CreateScheduleCommand toCommand() {
		return new CreateScheduleCommand(flightNumber, origin, destination, departureTime, arrivalTime, aircraftId,
				daysOfOperation);
	}

}
