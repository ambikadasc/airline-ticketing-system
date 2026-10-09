package com.airline.reservation.booking.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies {@link LookupMissLimiter} to the endpoints that take a booking reference (lookup, confirm,
 * cancel). It is a Spring MVC interceptor, registered by path pattern in {@link BookingWebConfig},
 * so it sees exactly the requests Spring routes to those endpoints; no path variant can reach the
 * controller around it. A refusal is an {@code ApiException}, so the error body comes from the
 * global handler in the standard shape.
 */
@Component
public class BookingLookupThrottle implements HandlerInterceptor {

	private final LookupMissLimiter limiter;

	public BookingLookupThrottle(LookupMissLimiter limiter) {
		this.limiter = limiter;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		limiter.check(request.getRemoteAddr());
		return true;
	}

	@Override
	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
			Exception ex) {
		if (response.getStatus() == HttpServletResponse.SC_NOT_FOUND) {
			limiter.recordMiss(request.getRemoteAddr());
		}
	}

}
