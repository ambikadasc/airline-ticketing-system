package com.airline.reservation.booking.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.airline.reservation.common.config.AirlineProperties;
import com.airline.reservation.common.error.ApiException;
import com.airline.reservation.common.error.ErrorCode;

/**
 * Keeps booking references hard to guess: a client that collects more than {@code maxMisses}
 * BOOKING_NOT_FOUND answers inside one window is refused (429) until the window ends. Only misses
 * count; a customer looking up their own booking is never affected. Successful requests cost
 * nothing, so this is not a rate limit on traffic, only on guessing.
 * <p>
 * State is per application instance (an attacker spread over many nodes gets N times the budget);
 * a cluster-wide limiter belongs at the edge. The client is {@code remoteAddr}: behind a reverse
 * proxy, configure {@code server.forward-headers-strategy} so that is the real client address.
 */
@Component
public class LookupMissLimiter {

	private static final Logger log = LoggerFactory.getLogger(LookupMissLimiter.class);

	/** Bound on memory: beyond this many tracked clients, expired windows are purged. */
	private static final int MAX_TRACKED_CLIENTS = 10_000;

	private final AirlineProperties.LookupThrottle settings;
	private final Clock clock;
	private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

	@Autowired
	public LookupMissLimiter(AirlineProperties properties, Clock clock) {
		this(properties.lookupThrottle(), clock);
	}

	LookupMissLimiter(AirlineProperties.LookupThrottle settings, Clock clock) {
		this.settings = settings;
		this.clock = clock;
	}

	/** Throws 429 RATE_LIMITED if the client has used up its misses for the current window. */
	public void check(String client) {
		Window window = windows.get(client);
		if (window == null) {
			return;
		}
		Instant now = clock.instant();
		if (window.hasEndedAt(now)) {
			windows.remove(client, window);
			return;
		}
		if (window.misses.get() >= settings.maxMisses()) {
			long secondsLeft = Math.max(1, window.secondsLeftAt(now));
			throw new ApiException(ErrorCode.RATE_LIMITED,
					"Too many unsuccessful booking lookups; try again in " + secondsLeft + " seconds",
					Duration.ofSeconds(secondsLeft));
		}
	}

	/** Called after a lookup, confirm or cancel answered BOOKING_NOT_FOUND. */
	public void recordMiss(String client) {
		Instant now = clock.instant();
		Window window = windows.compute(client,
				(key, current) -> current == null || current.hasEndedAt(now) ? new Window(now) : current);
		int misses = window.misses.incrementAndGet();
		if (misses == settings.maxMisses()) {
			// The client address only: the attempted references are never logged.
			log.warn("Client {} reached {} unsuccessful booking lookups within {}; refusing further lookups until the window ends",
					client, misses, settings.window());
		}
		if (windows.size() > MAX_TRACKED_CLIENTS) {
			purge(now);
		}
	}

	/** Forgets every client. For tests, which share one application context. */
	public void reset() {
		windows.clear();
	}

	private void purge(Instant now) {
		windows.entrySet().removeIf(entry -> entry.getValue().hasEndedAt(now));
		if (windows.size() > MAX_TRACKED_CLIENTS) {
			// ponytail: an attacker rotating through more than 10,000 addresses inside one window
			// resets everyone's count. Acceptable here; a cluster-wide limiter at the edge is the step up.
			windows.clear();
		}
	}

	private final class Window {

		private final Instant start;
		private final AtomicInteger misses = new AtomicInteger();

		Window(Instant start) {
			this.start = start;
		}

		boolean hasEndedAt(Instant now) {
			return !now.isBefore(start.plus(settings.window()));
		}

		long secondsLeftAt(Instant now) {
			Duration left = Duration.between(now, start.plus(settings.window()));
			return (left.toMillis() + 999) / 1000;
		}

	}

}
