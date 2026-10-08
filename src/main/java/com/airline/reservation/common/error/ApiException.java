package com.airline.reservation.common.error;

/**
 * An expected failure of a use case (unknown id, conflict, rule broken). The handler turns it
 * into a ProblemDetail with the code's HTTP status. The detail message is shown to the client,
 * so it must not contain internals.
 */
public class ApiException extends RuntimeException {

	private final ErrorCode code;

	public ApiException(ErrorCode code, String detail) {
		super(detail);
		this.code = code;
	}

	public ErrorCode getCode() {
		return code;
	}

}
