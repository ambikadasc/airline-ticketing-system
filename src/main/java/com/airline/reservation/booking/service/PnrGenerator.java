package com.airline.reservation.booking.service;

import java.security.SecureRandom;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * Generates 6-character booking references (PNRs). SecureRandom makes them unguessable, which
 * matters because the reference alone gives access to a booking. 32 symbols ^ 6 is about
 * 1 billion combinations.
 */
@Component
public class PnrGenerator {

	/** No 0/O or 1/I, which are easy to confuse when read out or typed. */
	private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
	private static final int LENGTH = 6;

	private final SecureRandom random = new SecureRandom();

	public String next() {
		return random.ints(LENGTH, 0, ALPHABET.length())
				.mapToObj(i -> String.valueOf(ALPHABET.charAt(i)))
				.collect(Collectors.joining());
	}

}
