package com.airline.reservation.aircraft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SeatLayoutTest {

	private final SeatLayout a320 = new SeatLayout(30, "ABCDEF");

	@Test
	void seatsAreListedRowByRowThenInLetterOrder() {
		assertThat(a320.seats())
				.hasSize(180)
				.startsWith("1A", "1B", "1C", "1D", "1E", "1F", "2A")
				.endsWith("30E", "30F")
				.doesNotHaveDuplicates();
	}

	@Test
	void letterOrderFollowsTheConfigurationNotTheAlphabet() {
		SeatLayout b777 = new SeatLayout(1, "ABCDEFGHJK");

		assertThat(b777.seats()).containsExactly("1A", "1B", "1C", "1D", "1E", "1F", "1G", "1H", "1J", "1K");
	}

	@ParameterizedTest
	@ValueSource(strings = {"1A", "12C", "30F"})
	void containsSeatsOnTheAircraft(String seat) {
		assertThat(a320.contains(seat)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {"0A", "31A", "1G", "A1", "1", "1AA", "01A", "1a", " 1A", "100A"})
	void doesNotContainSeatsOffTheAircraftOrMalformed(String seat) {
		assertThat(a320.contains(seat)).isFalse();
	}

	@ParameterizedTest
	@NullAndEmptySource
	void doesNotContainNullOrEmpty(String seat) {
		assertThat(a320.contains(seat)).isFalse();
	}

	@Test
	void totalSeatsIsRowsTimesLetters() {
		assertThat(a320.totalSeats()).isEqualTo(180);
		assertThat(new SeatLayout(40, "ABCDEFGHJK").totalSeats()).isEqualTo(400);
		assertThat(new SeatLayout(18, "ABCD").totalSeats()).isEqualTo(72);
	}

	@ParameterizedTest
	@ValueSource(ints = {0, -1, 100})
	void rejectsRowCountOutsideOneToNinetyNine(int rowCount) {
		assertThatIllegalArgumentException().isThrownBy(() -> new SeatLayout(rowCount, "ABCDEF"));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"abc", "AB1", "A B", "ABCA"})
	void rejectsSeatLettersThatAreNotDistinctCapitalLetters(String seatLetters) {
		assertThatIllegalArgumentException().isThrownBy(() -> new SeatLayout(30, seatLetters));
	}

}
