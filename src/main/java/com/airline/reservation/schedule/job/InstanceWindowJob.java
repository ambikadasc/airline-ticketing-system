package com.airline.reservation.schedule.job;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

	private static final Logger log = LoggerFactory.getLogger(InstanceWindowJob.class);

	private final ScheduleService scheduleService;

	public InstanceWindowJob(ScheduleService scheduleService) {
		this.scheduleService = scheduleService;
	}

	/**
	 * The startup top-up must never stop the application from starting: if it fails (e.g. another
	 * node is inserting the same dates and the lock wait exceeds lock_timeout), the daily job or
	 * {@code POST /api/v1/admin/instance-window/extend} will catch up.
	 */
	@Override
	public void run(ApplicationArguments args) {
		try {
			runWithLogId("startup");
		}
		catch (RuntimeException ex) {
			log.warn("Startup instance-window top-up failed; the daily job or the admin endpoint will catch up", ex);
		}
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
