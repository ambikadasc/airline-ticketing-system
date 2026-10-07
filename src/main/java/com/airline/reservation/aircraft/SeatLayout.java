package com.airline.reservation.aircraft;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An aircraft's fixed seat configuration: rows 1..rowCount, each with the seats named by
 * {@code seatLetters} in that order (e.g. 30 rows of "ABCDEF" gives 1A..30F).
 * <p>
 * This is the one place that defines which seats exist. The seat map lists {@link #seats()} and
 * booking validation asks {@link #contains(String)}.
 */
public record SeatLayout(int rowCount, String seatLetters) {

	/** Row 1-99 without a leading zero, then one capital letter, e.g. "12A". */
	private static final Pattern SEAT_LABEL = Pattern.compile("^([1-9]\\d?)([A-Z])$");

	public SeatLayout {
		if (rowCount < 1 || rowCount > 99) {
			throw new IllegalArgumentException("rowCount must be between 1 and 99: " + rowCount);
		}
		if (seatLetters == null || !seatLetters.matches("[A-Z]+")) {
			throw new IllegalArgumentException("seatLetters must be capital letters A-Z: " + seatLetters);
		}
		if (seatLetters.chars().distinct().count() != seatLetters.length()) {
			throw new IllegalArgumentException("seatLetters must not repeat a letter: " + seatLetters);
		}
	}

	/** Every seat label, row by row, and within a row in the configured letter order. */
	public List<String> seats() {
		List<String> seats = new ArrayList<>(totalSeats());
		for (int row = 1; row <= rowCount; row++) {
			for (char letter : seatLetters.toCharArray()) {
				seats.add(row + String.valueOf(letter));
			}
		}
		return seats;
	}

	/**
	 * Whether the seat exists on this aircraft. Expects an already normalised label
	 * (trimmed, upper case); anything else is simply not a seat.
	 */
	public boolean contains(String seat) {
		if (seat == null) {
			return false;
		}
		Matcher matcher = SEAT_LABEL.matcher(seat);
		if (!matcher.matches()) {
			return false;
		}
		int row = Integer.parseInt(matcher.group(1));
		char letter = matcher.group(2).charAt(0);
		return row <= rowCount && seatLetters.indexOf(letter) >= 0;
	}

	public int totalSeats() {
		return rowCount * seatLetters.length();
	}

}
