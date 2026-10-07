package com.airline.reservation.aircraft;

import java.util.Optional;

import org.springframework.data.repository.Repository;

/**
 * Read-only access to seeded aircraft. Extends the bare {@link Repository} so no save or
 * delete methods exist.
 */
public interface AircraftRepository extends Repository<Aircraft, Long> {

	Optional<Aircraft> findById(Long id);

}
