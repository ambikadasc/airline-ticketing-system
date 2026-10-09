package com.airline.reservation.booking.api;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.airline.reservation.MutableClock;
import com.airline.reservation.common.config.AirlineProperties;
import com.airline.reservation.common.error.ApiException;
import com.airline.reservation.common.error.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** At most N unsuccessful lookups per client per window; then 429 until the window ends. */
class LookupMissLimiterTest {

	private static final String CLIENT = "203.0.113.7";

	private final MutableClock clock = new MutableClock();
	private final LookupMissLimiter limiter = new LookupMissLimiter(
			new AirlineProperties.LookupThrottle(10, Duration.ofMinutes(1)), clock);

	@Test
	void allowsUpToTheMaximumNumberOfMissesInAWindow() {
		for (int i = 0; i < 10; i++) {
			assertThatCode(() -> limiter.check(CLIENT)).doesNotThrowAnyException();
			limiter.recordMiss(CLIENT);
		}

		assertThatThrownBy(() -> limiter.check(CLIENT))
				.isInstanceOf(ApiException.class)
				.satisfies(ex -> {
					ApiException api = (ApiException) ex;
					assertThat(api.getCode()).isEqualTo(ErrorCode.RATE_LIMITED);
					assertThat(api.retryAfter()).contains(Duration.ofSeconds(60));
				});
	}

	@Test
	void retryAfterIsTheTimeLeftInTheWindow() {
		missTenTimes(CLIENT);
		clock.advance(Duration.ofSeconds(45));

		assertThatThrownBy(() -> limiter.check(CLIENT))
				.isInstanceOf(ApiException.class)
				.satisfies(ex -> assertThat(((ApiException) ex).retryAfter()).contains(Duration.ofSeconds(15)));
	}

	@Test
	void theLimitLiftsWhenTheWindowEnds() {
		missTenTimes(CLIENT);
		clock.advance(Duration.ofSeconds(60));

		assertThatCode(() -> limiter.check(CLIENT)).doesNotThrowAnyException();
	}

	@Test
	void anotherClientIsNotAffected() {
		missTenTimes(CLIENT);

		assertThatCode(() -> limiter.check("198.51.100.9")).doesNotThrowAnyException();
	}

	@Test
	void resetClearsEveryClient() {
		missTenTimes(CLIENT);

		limiter.reset();

		assertThatCode(() -> limiter.check(CLIENT)).doesNotThrowAnyException();
	}

	private void missTenTimes(String client) {
		for (int i = 0; i < 10; i++) {
			limiter.recordMiss(client);
		}
	}

}
