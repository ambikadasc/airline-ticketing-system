package com.airline.reservation.booking.api;

import java.net.URI;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.airline.reservation.booking.service.BookingResult;
import com.airline.reservation.booking.service.BookingService;

@Tag(name = "Bookings")
@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

	private final BookingService bookingService;

	public BookingController(BookingService bookingService) {
		this.bookingService = bookingService;
	}

	@Operation(summary = "Book one or more seats on a flight (all or nothing)")
	@PostMapping
	public ResponseEntity<BookingResult> create(@Valid @RequestBody CreateBookingRequest request) {
		BookingResult booking = bookingService.createBooking(request.toCommand());
		return ResponseEntity.created(URI.create("/api/v1/bookings/" + booking.bookingReference())).body(booking);
	}

	@Operation(summary = "Confirm a held booking before its hold expires (only needed with the seat hold enabled)")
	@PostMapping("/{reference}/confirm")
	public BookingResult confirm(@PathVariable String reference) {
		return bookingService.confirmBooking(reference);
	}

	@Operation(summary = "Cancel a booking and release its seats (repeating it returns the same result)")
	@PostMapping("/{reference}/cancel")
	public BookingResult cancel(@PathVariable String reference) {
		return bookingService.cancelBooking(reference);
	}

	@Operation(summary = "Fetch a booking by its reference")
	@GetMapping("/{reference}")
	public BookingResult get(@PathVariable String reference) {
		return bookingService.getBooking(reference);
	}

}
