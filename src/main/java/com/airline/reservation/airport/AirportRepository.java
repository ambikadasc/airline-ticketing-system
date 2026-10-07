package com.airline.reservation.airport;

import java.util.Optional;

import org.springframework.data.repository.Repository;

/**
 * Read-only access to seeded airports. Extends the bare {@link Repository} so no save or
 * delete methods exist.
 */
public interface AirportRepository extends Repository<Airport, String> {

	Optional<Airport> findById(String code);

	boolean existsById(String code);

}
