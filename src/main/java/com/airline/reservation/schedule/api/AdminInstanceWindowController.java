package com.airline.reservation.schedule.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.airline.reservation.schedule.service.InstanceWindowResult;
import com.airline.reservation.schedule.service.ScheduleService;

/**
 * Lets an operator top up the flight-instance window on demand, e.g. after lengthening the booking
 * window or after downtime, instead of waiting for the daily job or restarting.
 */
@Tag(name = "Admin: schedules")
@RestController
@RequestMapping("/api/v1/admin/instance-window")
public class AdminInstanceWindowController {

	private final ScheduleService scheduleService;

	public AdminInstanceWindowController(ScheduleService scheduleService) {
		this.scheduleService = scheduleService;
	}

	@Operation(summary = "Generate any missing flight instances up to the end of the booking window "
			+ "(idempotent; the same work as the daily job)")
	@PostMapping("/extend")
	public InstanceWindowResult extend() {
		return scheduleService.extendInstanceWindow();
	}

}
