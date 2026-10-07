package com.airline.reservation.aircraft;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * An aircraft with a fixed seat configuration. Seeded by migration and read-only: no setters.
 */
@Entity
public class Aircraft {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String code;

	private String aircraftType;

	private int rowCount;

	private String seatLetters;

	protected Aircraft() {
		// for JPA
	}

	public SeatLayout seatLayout() {
		return new SeatLayout(rowCount, seatLetters);
	}

	public int totalSeats() {
		return seatLayout().totalSeats();
	}

	public Long getId() {
		return id;
	}

	public String getCode() {
		return code;
	}

	public String getAircraftType() {
		return aircraftType;
	}

}
