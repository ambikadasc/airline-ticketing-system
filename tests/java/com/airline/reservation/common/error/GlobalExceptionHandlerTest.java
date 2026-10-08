package com.airline.reservation.common.error;

import java.sql.SQLException;
import java.time.Duration;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import com.airline.reservation.common.config.AirlineProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mappings that cannot be reached reliably through the API while the flight lock is in place:
 * a unique-constraint violation, and an unexpected exception.
 */
class GlobalExceptionHandlerTest {

	private final GlobalExceptionHandler handler = new GlobalExceptionHandler(new AirlineProperties(365, 9,
			"0 5 0 * * *", Duration.ofSeconds(1), new AirlineProperties.SeatHold(false, Duration.ofMinutes(10))));
	private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/bookings");

	@Test
	void aViolatedSeatIndexIsASeatConflict() {
		ResponseEntity<ProblemDetail> response = handler.handleDataIntegrity(violationOf("uq_active_seat"), request);

		assertThat(response.getStatusCode().value()).isEqualTo(409);
		assertThat(response.getBody().getProperties()).containsEntry("code", "SEAT_UNAVAILABLE");
		assertThat(response.getBody().getDetail()).doesNotContain("uq_active_seat");
	}

	@Test
	void aViolatedFlightNumberConstraintIsADuplicate() {
		ResponseEntity<ProblemDetail> response = handler.handleDataIntegrity(violationOf("uq_schedule_flight_number"),
				request);

		assertThat(response.getStatusCode().value()).isEqualTo(409);
		assertThat(response.getBody().getProperties()).containsEntry("code", "DUPLICATE_FLIGHT_NUMBER");
	}

	@Test
	void aBookingReferenceClashIsATemporaryConditionToRetry() {
		ResponseEntity<ProblemDetail> response = handler.handleDataIntegrity(violationOf("uq_booking_reference"),
				request);

		assertThat(response.getStatusCode().value()).isEqualTo(503);
		assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
		assertThat(response.getBody().getProperties()).containsEntry("code", "RETRY_LATER");
		assertThat(response.getBody().getDetail()).contains("nothing was booked").doesNotContain("uq_");
	}

	@Test
	void anyOtherConstraintIsAnInternalErrorWithAGenericMessage() {
		ResponseEntity<ProblemDetail> response = handler.handleDataIntegrity(violationOf("chk_instance_seats"), request);

		assertThat(response.getStatusCode().value()).isEqualTo(500);
		assertThat(response.getBody().getProperties()).containsEntry("code", "INTERNAL_ERROR");
		assertThat(response.getBody().getDetail()).isEqualTo("An unexpected error occurred");
	}

	@Test
	void anUnexpectedExceptionNeverReachesTheClient() {
		ResponseEntity<ProblemDetail> response = handler.handleUnexpected(
				new IllegalStateException("secret SQL: SELECT * FROM booking"), request);

		assertThat(response.getStatusCode().value()).isEqualTo(500);
		assertThat(response.getBody().getDetail()).isEqualTo("An unexpected error occurred");
		assertThat(response.getBody().toString()).doesNotContain("secret", "SELECT", "IllegalStateException");
	}

	private static DataIntegrityViolationException violationOf(String constraint) {
		return new DataIntegrityViolationException("could not execute statement",
				new ConstraintViolationException("duplicate key", new SQLException("duplicate key"), constraint));
	}

}
