package com.airline.reservation.common.config;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed binding of the {@code airline.*} properties in application.yml.
 */
@Validated
@ConfigurationProperties("airline")
public record AirlineProperties(
		@Positive int bookingWindowDays,
		@Positive int maxSeatsPerBooking,
		@NotBlank String instanceJobCron,
		@Valid @NotNull SeatHold seatHold) {

	/**
	 * Optional seat hold. With {@code enabled = false} (the default) bookings are confirmed immediately.
	 */
	public record SeatHold(boolean enabled, @NotNull Duration ttl) {
	}

}
