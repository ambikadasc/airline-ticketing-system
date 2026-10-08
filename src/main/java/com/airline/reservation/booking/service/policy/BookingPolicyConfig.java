package com.airline.reservation.booking.service.policy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.airline.reservation.common.config.AirlineProperties;

/**
 * Picks the booking policy once, at startup. This is the only place the seat-hold flag is read:
 * the rest of the code works the same way with either policy.
 */
@Configuration(proxyBeanMethods = false)
public class BookingPolicyConfig {

	@Bean
	BookingPolicy bookingPolicy(AirlineProperties properties) {
		AirlineProperties.SeatHold seatHold = properties.seatHold();
		return seatHold.enabled() ? new SeatHoldPolicy(seatHold.ttl()) : new ImmediateConfirmationPolicy();
	}

}
