package com.airline.reservation.schedule.job;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.airline.reservation.schedule.service.ScheduleService;

/**
 * Keeps the 365-day window of flight instances filled: once a day, and once at startup to heal
 * any gap left while the application was down. The work is idempotent, so overlapping runs on
 * several nodes are harmless.
 */
@Component
public class InstanceWindowJob implements ApplicationRunner {

	private final ScheduleService scheduleService;

	public InstanceWindowJob(ScheduleService scheduleService) {
		this.scheduleService = scheduleService;
	}

	@Override
	public void run(ApplicationArguments args) {
		scheduleService.extendInstanceWindow();
	}

	@Scheduled(cron = "${airline.instance-job-cron}", zone = "UTC")
	public void runDaily() {
		scheduleService.extendInstanceWindow();
	}

}
