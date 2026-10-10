package com.airline.reservation.booking.service;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.airline.reservation.booking.domain.PassengerSeat;

import static org.assertj.core.api.Assertions.assertThat;

/** The fingerprint decides whether a repeated Idempotency-Key carries the same booking request. */
class RequestFingerprintTest {

	private static final List<PassengerSeat> TWO_SEATS = List.of(
			new PassengerSeat("Ayesha Khan", "12A"), new PassengerSeat("Bilal Khan", "12B"));

	@Test
	void theSameRequestAlwaysHasTheSameFingerprint() {
		assertThat(RequestFingerprint.of(42L, TWO_SEATS)).isEqualTo(RequestFingerprint.of(42L, TWO_SEATS));
	}

	@Test
	void isSixtyFourHexCharacters() {
		assertThat(RequestFingerprint.of(42L, TWO_SEATS)).matches("[0-9a-f]{64}");
	}

	@Test
	void aDifferentFlightSeatNameOrOrderIsADifferentRequest() {
		String original = RequestFingerprint.of(42L, TWO_SEATS);

		assertThat(RequestFingerprint.of(43L, TWO_SEATS)).isNotEqualTo(original);
		assertThat(RequestFingerprint.of(42L, List.of(
				new PassengerSeat("Ayesha Khan", "12A"), new PassengerSeat("Bilal Khan", "12C")))).isNotEqualTo(original);
		assertThat(RequestFingerprint.of(42L, List.of(
				new PassengerSeat("Ayesha Khan", "12A"), new PassengerSeat("Bilal Ahmed", "12B")))).isNotEqualTo(original);
		assertThat(RequestFingerprint.of(42L, List.of(
				new PassengerSeat("Bilal Khan", "12B"), new PassengerSeat("Ayesha Khan", "12A")))).isNotEqualTo(original);
	}

	@Test
	void fieldBoundariesCannotBeConfused() {
		// "1A" + "B" must not fingerprint like "1" + "AB": every value is delimited before hashing.
		String a = RequestFingerprint.of(1L, List.of(new PassengerSeat("AB", "1A")));
		String b = RequestFingerprint.of(1L, List.of(new PassengerSeat("B", "1AA")));
		assertThat(a).isNotEqualTo(b);
	}

}
