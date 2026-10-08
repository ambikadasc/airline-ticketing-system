package com.airline.reservation;

import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared setup for integration tests: the same PostgreSQL engine as production, plus a test clock.
 * Import it with {@code @Import(TestcontainersConfiguration.class)}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	/** Monday 5 January 2026, 00:00 UTC. Every test date is computed from this instant. */
	public static final Instant TEST_NOW = Instant.parse("2026-01-05T00:00:00Z");

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:16"));
	}

	/** Stands still at TEST_NOW unless a test moves it; reset before every test by IntegrationTest. */
	@Bean
	@Primary
	MutableClock testClock() {
		return new MutableClock();
	}

}
