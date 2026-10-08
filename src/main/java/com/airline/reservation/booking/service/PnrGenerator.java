package com.airline.reservation.booking.service;

import java.security.SecureRandom;

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
		StringBuilder reference = new StringBuilder(LENGTH);
		for (int i = 0; i < LENGTH; i++) {
			reference.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
		}
		return reference.toString();
	}

}
