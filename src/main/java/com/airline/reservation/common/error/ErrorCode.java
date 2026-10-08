package com.airline.reservation.common.error;

import org.springframework.http.HttpStatus;

/**
 * Machine-readable error codes returned in the {@code code} property of every error response,
 * each with its HTTP status. Clients branch on the code, not the status.
 */
public enum ErrorCode {

	VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
	OUTSIDE_BOOKING_WINDOW(HttpStatus.BAD_REQUEST),
	INVALID_SEAT(HttpStatus.BAD_REQUEST),

	AIRPORT_NOT_FOUND(HttpStatus.NOT_FOUND),
	AIRCRAFT_NOT_FOUND(HttpStatus.NOT_FOUND),
	SCHEDULE_NOT_FOUND(HttpStatus.NOT_FOUND),
	FLIGHT_NOT_FOUND(HttpStatus.NOT_FOUND),
	BOOKING_NOT_FOUND(HttpStatus.NOT_FOUND),
	RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),

	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
	NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE),
	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),

	DUPLICATE_FLIGHT_NUMBER(HttpStatus.CONFLICT),
	SEAT_UNAVAILABLE(HttpStatus.CONFLICT),
	FLIGHT_NOT_BOOKABLE(HttpStatus.CONFLICT),
	BOOKING_NOT_CANCELLABLE(HttpStatus.CONFLICT),
	BOOKING_NOT_CONFIRMABLE(HttpStatus.CONFLICT),
	HOLD_EXPIRED(HttpStatus.CONFLICT),

	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),
	LOCK_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE),
	/** Temporary failure where nothing was changed (e.g. a booking-reference clash); retrying is safe. */
	RETRY_LATER(HttpStatus.SERVICE_UNAVAILABLE);

	private final HttpStatus status;

	ErrorCode(HttpStatus status) {
		this.status = status;
	}

	public HttpStatus status() {
		return status;
	}

}
