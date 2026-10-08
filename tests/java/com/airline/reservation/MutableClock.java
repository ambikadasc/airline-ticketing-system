package com.airline.reservation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A test clock that stands still at {@link TestcontainersConfiguration#TEST_NOW} until a test moves
 * it (e.g. past a seat hold's expiry). {@link IntegrationTest} resets it before every test.
 */
public class MutableClock extends Clock {

	private volatile Instant now = TestcontainersConfiguration.TEST_NOW;

	public void advance(Duration duration) {
		now = now.plus(duration);
	}

	public void set(Instant instant) {
		now = instant;
	}

	public void reset() {
		now = TestcontainersConfiguration.TEST_NOW;
	}

	@Override
	public Instant instant() {
		return now;
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		throw new UnsupportedOperationException("The test clock is always UTC");
	}

}
