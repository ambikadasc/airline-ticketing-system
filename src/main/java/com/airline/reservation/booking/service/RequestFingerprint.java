package com.airline.reservation.booking.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import com.airline.reservation.booking.domain.PassengerSeat;

/**
 * SHA-256 of a booking request (flight and passengers, after normalisation), stored with the
 * booking next to its Idempotency-Key. A repeated key with a different fingerprint is a different
 * request wearing the same key, and is refused.
 */
final class RequestFingerprint {

	private RequestFingerprint() {
	}

	/** Hex-encoded SHA-256; every value is delimited so that field boundaries cannot be confused. */
	static String of(Long flightInstanceId, List<PassengerSeat> passengers) {
		StringBuilder canonical = new StringBuilder().append(flightInstanceId).append('\n');
		for (PassengerSeat passenger : passengers) {
			canonical.append(passenger.seatNumber()).append('\u001F').append(passenger.name()).append('\n');
		}
		try {
			MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(sha256.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is part of every Java runtime", ex);
		}
	}

}
