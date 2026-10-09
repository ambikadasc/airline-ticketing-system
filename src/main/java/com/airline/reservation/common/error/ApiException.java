package com.airline.reservation.common.error;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * An expected failure of a use case (unknown id, conflict, rule broken). The handler turns it
 * into a ProblemDetail with the code's HTTP status. The detail message is shown to the client,
 * so it must not contain internals.
 */
public class ApiException extends RuntimeException {

	private final ErrorCode code;
	private final Duration retryAfter;

	public ApiException(ErrorCode code, String detail) {
		this(code, detail, null);
	}

	/** With a Retry-After the response should carry (e.g. the rest of a rate-limit window). */
	public ApiException(ErrorCode code, String detail, Duration retryAfter) {
		super(detail);
		this.code = code;
		this.retryAfter = retryAfter;
	}

	public Optional<Duration> retryAfter() {
		return Optional.ofNullable(retryAfter);
	}

	public ErrorCode getCode() {
		return code;
	}

	/** Extra fields for the error body, e.g. the seats that were unavailable. None by default. */
	public Map<String, Object> extraProperties() {
		return Map.of();
	}

}
