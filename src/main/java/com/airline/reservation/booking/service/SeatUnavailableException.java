package com.airline.reservation.booking.service;

import java.util.List;
import java.util.Map;

import com.airline.reservation.common.error.ApiException;
import com.airline.reservation.common.error.ErrorCode;

/** 409: one or more requested seats are already taken. The response lists them. */
public class SeatUnavailableException extends ApiException {

	private final List<String> unavailableSeats;

	public SeatUnavailableException(List<String> unavailableSeats) {
		super(ErrorCode.SEAT_UNAVAILABLE, "Seats already booked: " + String.join(", ", unavailableSeats));
		this.unavailableSeats = List.copyOf(unavailableSeats);
	}

	public List<String> getUnavailableSeats() {
		return unavailableSeats;
	}

	@Override
	public Map<String, Object> extraProperties() {
		return Map.of("unavailableSeats", unavailableSeats);
	}

}
