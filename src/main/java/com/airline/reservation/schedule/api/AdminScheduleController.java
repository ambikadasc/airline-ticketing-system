package com.airline.reservation.schedule.api;

import java.net.URI;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.airline.reservation.schedule.service.ScheduleResult;
import com.airline.reservation.schedule.service.ScheduleService;

@Tag(name = "Admin: schedules")
@RestController
@RequestMapping("/api/v1/admin/schedules")
public class AdminScheduleController {

	private final ScheduleService scheduleService;

	public AdminScheduleController(ScheduleService scheduleService) {
		this.scheduleService = scheduleService;
	}

	@Operation(summary = "Create a flight schedule and generate its flight instances for the next 365 days")
	@PostMapping
	public ResponseEntity<ScheduleResult> create(@Valid @RequestBody CreateScheduleRequest request) {
		ScheduleResult created = scheduleService.createSchedule(request.toCommand());
		return ResponseEntity.created(URI.create("/api/v1/admin/schedules/" + created.id())).body(created);
	}

	@Operation(summary = "Fetch a flight schedule")
	@GetMapping("/{id}")
	public ScheduleResult get(@PathVariable Long id) {
		return scheduleService.getSchedule(id);
	}

}
