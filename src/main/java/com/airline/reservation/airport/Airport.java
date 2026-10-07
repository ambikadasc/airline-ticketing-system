package com.airline.reservation.airport;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/**
 * An airport, identified by its IATA code. Seeded by migration and read-only: no setters.
 */
@Entity
public class Airport {

	@Id
	private String code;

	private String name;

	private String city;

	private String country;

	protected Airport() {
		// for JPA
	}

	public String getCode() {
		return code;
	}

	public String getName() {
		return name;
	}

	public String getCity() {
		return city;
	}

	public String getCountry() {
		return country;
	}

}
