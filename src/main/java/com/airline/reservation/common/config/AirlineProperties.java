package com.airline.reservation.common.config;

import java.time.Duration;
import java.time.LocalDate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed binding of the {@code airline.*} properties in application.yml. Every value can be changed
 * without touching code, e.g. through an environment variable such as
 * {@code AIRLINE_BOOKING_WINDOW_DAYS=180}.
 */
@Validated
@ConfigurationProperties("airline")
public record AirlineProperties(
		/* How many days ahead flights can be searched and booked (today plus this many days). */
		@Positive int bookingWindowDays,
		@Positive int maxSeatsPerBooking,
		@NotBlank String instanceJobCron,
		/* Sent as Retry-After with a 503 (flight lock not granted in time, or a booking-reference clash). */
		@NotNull Duration retryAfter,
		@Valid @NotNull SeatHold seatHold,
		@Valid @NotNull LookupThrottle lookupThrottle) {

	/**
	 * The last date that can be searched or booked: today plus the booking window. The single
	 * definition of the window, used by instance generation, search and booking.
	 */
	public LocalDate lastBookableDate(LocalDate today) {
		return today.plusDays(bookingWindowDays);
	}

	/**
	 * Optional seat hold. With {@code enabled = false} (the default) bookings are confirmed immediately.
	 */
	public record SeatHold(boolean enabled, @NotNull Duration ttl) {
	}

	/**
	 * Per-client limit on unsuccessful booking lookups (404s on lookup, confirm and cancel): the
	 * booking reference is the only credential, so guessing it must stay slow.
	 */
	public record LookupThrottle(@Positive int maxMisses, @NotNull Duration window) {
	}

}
