package com.airline.reservation.booking.service;

import org.junit.jupiter.api.RepeatedTest;

import static org.assertj.core.api.Assertions.assertThat;

class PnrGeneratorTest {

	private final PnrGenerator generator = new PnrGenerator();

	@RepeatedTest(100)
	void referenceIsSixCharactersFromTheUnambiguousAlphabet() {
		String reference = generator.next();

		// No 0/O or 1/I: they are easy to confuse when read out or typed.
		assertThat(reference).matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}");
	}

}
