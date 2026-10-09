package com.airline.reservation;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.airline.reservation.booking.api.LookupMissLimiter;

/**
 * Base class for integration tests. Every subclass shares one Spring context and therefore one
 * PostgreSQL container.
 * <p>
 * Deliberately not {@code @Transactional}: tests must see committed data, as production does,
 * and concurrency tests would deadlock inside a test-managed transaction. Instead each test
 * starts from empty transactional tables; the seeded airports and aircraft are left alone.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

	@Autowired
	protected MockMvc mockMvc;

	@Autowired
	protected JdbcTemplate jdbcTemplate;

	@Autowired
	protected MutableClock clock;

	@Autowired
	private LookupMissLimiter lookupMissLimiter;

	@BeforeEach
	void cleanTransactionalTables() {
		clock.reset();
		lookupMissLimiter.reset();
		jdbcTemplate.execute("TRUNCATE booking_seat, booking, flight_instance, flight_schedule_day, flight_schedule "
				+ "RESTART IDENTITY CASCADE");
	}

}
