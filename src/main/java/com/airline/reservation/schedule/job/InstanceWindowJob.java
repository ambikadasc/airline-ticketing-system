package com.airline.reservation.schedule.job;

import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.airline.reservation.common.logging.RequestIdFilter;
import com.airline.reservation.schedule.service.ScheduleService;

/**
 * Keeps flight instances generated for the whole booking window (airline.booking-window-days):
 * once a day, and once at startup to heal any gap left while the application was down or after the
 * window was lengthened. The work is idempotent, so overlapping runs on several nodes are harmless.
 * <p>
 * Each run gets its own id in the logging context (e.g. {@code job-daily-1a2b3c4d}), shown where an
 * HTTP request would show its request id, so the lines of one run can be grouped.
 */
@Component
public class InstanceWindowJob implements ApplicationRunner {

	private final ScheduleService scheduleService;

	public InstanceWindowJob(ScheduleService scheduleService) {
		this.scheduleService = scheduleService;
	}

	@Override
	public void run(ApplicationArguments args) {
		runWithLogId("startup");
	}

	@Scheduled(cron = "${airline.instance-job-cron}", zone = "UTC")
	public void runDaily() {
		runWithLogId("daily");
	}

	private void runWithLogId(String trigger) {
		String runId = "job-" + trigger + "-" + UUID.randomUUID().toString().substring(0, 8);
		MDC.put(RequestIdFilter.MDC_KEY, runId);
		try {
			scheduleService.extendInstanceWindow();
		}
		finally {
			MDC.remove(RequestIdFilter.MDC_KEY);
		}
	}

}
