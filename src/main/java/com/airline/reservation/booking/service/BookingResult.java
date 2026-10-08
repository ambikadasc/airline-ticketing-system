package com.airline.reservation.booking.service;

import java.time.LocalDate;
import java.util.List;

import com.airline.reservation.booking.domain.Booking;
import com.airline.reservation.booking.domain.BookingStatus;
import com.airline.reservation.flight.domain.FlightInstance;

/** A booking as returned by the API: every field the brief asks for. */
public record BookingResult(
		String bookingReference,
		Long flightInstanceId,
		String flightNumber,
		LocalDate flightDate,
		int passengerCount,
		List<SeatResult> seats,
		BookingStatus status) {

	public record SeatResult(String seatNumber, String passengerName) {
	}

	static BookingResult from(Booking booking, FlightInstance flight) {
		List<SeatResult> seats = booking.getSeats().stream()
				.map(seat -> new SeatResult(seat.getSeatNumber(), seat.getPassengerName()))
				.toList();
		return new BookingResult(booking.getReference(), flight.getId(), flight.getFlightNumber(),
				flight.getFlightDate(), booking.getPassengerCount(), seats, booking.getStatus());
	}

}
