package com.airline.reservation.schedule.job;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.DefaultApplicationArguments;

import com.airline.reservation.schedule.service.InstanceWindowResult;
import com.airline.reservation.schedule.service.ScheduleService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Each job run logs under its own id, and the id never leaks past the run. */
class InstanceWindowJobLogIdTest {

	private final ScheduleService scheduleService = mock(ScheduleService.class);
	private final InstanceWindowJob job = new InstanceWindowJob(scheduleService);
	private final AtomicReference<String> idDuringRun = new AtomicReference<>();

	@Test
	void aDailyRunLogsUnderItsOwnId() {
		captureIdDuringRun();

		job.runDaily();

		assertThat(idDuringRun.get()).matches("job-daily-[0-9a-f]{8}");
		assertThat(MDC.get("requestId")).isNull();
	}

	@Test
	void theStartupRunLogsUnderItsOwnId() {
		captureIdDuringRun();

		job.run(new DefaultApplicationArguments());

		assertThat(idDuringRun.get()).matches("job-startup-[0-9a-f]{8}");
		assertThat(MDC.get("requestId")).isNull();
	}

	@Test
	void theIdIsRemovedEvenWhenTheRunFails() {
		when(scheduleService.extendInstanceWindow()).thenThrow(new IllegalStateException("database down"));

		assertThatIllegalStateException().isThrownBy(job::runDaily);
		assertThat(MDC.get("requestId")).isNull();
	}

	private void captureIdDuringRun() {
		when(scheduleService.extendInstanceWindow()).thenAnswer(invocation -> {
			idDuringRun.set(MDC.get("requestId"));
			return new InstanceWindowResult(0, LocalDate.of(2027, 1, 5));
		});
	}

}
