package com.airline.reservation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.airline.reservation.aircraft.Aircraft;
import com.airline.reservation.aircraft.AircraftRepository;
import com.airline.reservation.airport.Airport;
import com.airline.reservation.airport.AirportRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The migrations create the schema and seed the reference data, and the entities map onto it
 * (the context only starts if ddl-auto=validate accepts them).
 */
class ReferenceDataTest extends IntegrationTest {

	@Autowired
	private AirportRepository airportRepository;

	@Autowired
	private AircraftRepository aircraftRepository;

	@Test
	void seedsTenAirportsAndThreeAircraft() {
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM airport", Long.class)).isEqualTo(10);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM aircraft", Long.class)).isEqualTo(3);
	}

	@Test
	void mapsAnAirport() {
		Airport dubai = airportRepository.findById("DXB").orElseThrow();

		assertThat(dubai.getName()).isEqualTo("Dubai International Airport");
		assertThat(dubai.getCity()).isEqualTo("Dubai");
		assertThat(dubai.getCountry()).isEqualTo("United Arab Emirates");
		assertThat(airportRepository.existsById("LHR")).isTrue();
	}

	@Test
	void mapsEachAircraftWithItsSeatLayout() {
		Aircraft a320 = aircraftRepository.findById(1L).orElseThrow();

		assertThat(a320.getAircraftType()).isEqualTo("A320");
		assertThat(a320.seatLayout().seats()).startsWith("1A").endsWith("30F");
		assertThat(a320.totalSeats()).isEqualTo(180);
		assertThat(aircraftRepository.findById(2L).orElseThrow().totalSeats()).isEqualTo(400);
		assertThat(aircraftRepository.findById(3L).orElseThrow().totalSeats()).isEqualTo(72);
	}

	@Test
	void unknownIdentifiersAreNotFound() {
		assertThat(airportRepository.findById("XXX")).isEmpty();
		assertThat(airportRepository.existsById("XXX")).isFalse();
		assertThat(aircraftRepository.findById(99L)).isEmpty();
	}

}
