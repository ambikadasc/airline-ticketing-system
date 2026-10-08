package com.airline.reservation;

import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.airline.reservation.common.config.AirlineProperties;

import static org.assertj.core.api.Assertions.assertThat;

class AirlineReservationApplicationTests extends IntegrationTest {

	@Autowired
	private Clock injectedClock;

	@Autowired
	private AirlineProperties properties;

	@Test
	void contextLoadsAgainstPostgres() {
	}

	@Test
	void injectedClockIsTheFixedTestClock() {
		assertThat(injectedClock.instant()).isEqualTo(TestcontainersConfiguration.TEST_NOW);
	}

	@Test
	void airlinePropertiesBindTheDefaults() {
		assertThat(properties.bookingWindowDays()).isEqualTo(365);
		assertThat(properties.maxSeatsPerBooking()).isEqualTo(9);
		assertThat(properties.seatHold().enabled()).isFalse();
		assertThat(properties.seatHold().ttl()).isEqualTo(Duration.ofMinutes(10));
	}

}
