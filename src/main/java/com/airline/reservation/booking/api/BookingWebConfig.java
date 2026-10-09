package com.airline.reservation.booking.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Wires the lookup throttle onto the booking-reference endpoints only; creating a booking is not throttled. */
@Configuration(proxyBeanMethods = false)
public class BookingWebConfig implements WebMvcConfigurer {

	private final BookingLookupThrottle lookupThrottle;

	public BookingWebConfig(BookingLookupThrottle lookupThrottle) {
		this.lookupThrottle = lookupThrottle;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(lookupThrottle)
				.addPathPatterns("/api/v1/bookings/*", "/api/v1/bookings/*/confirm", "/api/v1/bookings/*/cancel");
	}

}
