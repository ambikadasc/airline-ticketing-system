package com.airline.reservation.booking.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.airline.reservation.booking.domain.Booking;
import com.airline.reservation.booking.domain.BookingStatus;
import com.airline.reservation.flight.domain.FlightInstance;

/**
 * A booking as returned by the API: every field the brief asks for. {@code holdExpiresAt} appears
 * only while the booking is HELD (never with the seat hold disabled).
 */
public record BookingResult(
		String bookingReference,
		Long flightInstanceId,
		String flightNumber,
		LocalDate flightDate,
		int passengerCount,
		List<SeatResult> seats,
		BookingStatus status,
		@JsonInclude(JsonInclude.Include.NON_NULL) Instant holdExpiresAt) {

	public record SeatResult(String seatNumber, String passengerName) {
	}

	static BookingResult from(Booking booking, FlightInstance flight, Instant now) {
		List<SeatResult> seats = booking.getSeats().stream()
				.map(seat -> new SeatResult(seat.getSeatNumber(), seat.getPassengerName()))
				.toList();
		BookingStatus status = booking.effectiveStatus(now);
		Instant holdExpiresAt = status == BookingStatus.HELD ? booking.getHoldExpiresAt() : null;
		return new BookingResult(booking.getReference(), flight.getId(), flight.getFlightNumber(),
				flight.getFlightDate(), booking.getPassengerCount(), seats, status, holdExpiresAt);
	}

}
