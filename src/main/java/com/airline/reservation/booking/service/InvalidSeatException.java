package com.airline.reservation.booking.service;

import java.util.List;
import java.util.Map;

import com.airline.reservation.common.error.ApiException;
import com.airline.reservation.common.error.ErrorCode;

/** 400: one or more requested seats do not exist on the aircraft. The response lists them. */
public class InvalidSeatException extends ApiException {

	private final List<String> invalidSeats;

	public InvalidSeatException(List<String> invalidSeats) {
		super(ErrorCode.INVALID_SEAT, "Seats not on this aircraft: " + String.join(", ", invalidSeats));
		this.invalidSeats = List.copyOf(invalidSeats);
	}

	@Override
	public Map<String, Object> extraProperties() {
		return Map.of("invalidSeats", invalidSeats);
	}

}
