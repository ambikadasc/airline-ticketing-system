package com.airline.reservation.booking.service;

import java.util.List;

import com.airline.reservation.booking.domain.PassengerSeat;

/** Input to {@link BookingService#createBooking}: shape-validated, seat numbers not yet normalised. */
public record CreateBookingCommand(Long flightInstanceId, List<PassengerSeat> passengers) {
}
