package com.airline.reservation.flight.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.airline.reservation.flight.domain.FlightInstance;

/**
 * Instances are created by {@link FlightInstanceBulkWriter}; this repository reads them.
 */
public interface FlightInstanceRepository extends Repository<FlightInstance, Long> {

	Optional<FlightInstance> findById(Long id);

	/**
	 * Loads the flight and locks its row (SELECT ... FOR UPDATE) until the transaction ends. Every
	 * booking and cancellation takes this lock first, so writes on one flight run one at a time
	 * while other flights stay parallel. Must be the first load of the flight in the transaction:
	 * an already-loaded copy would be returned as it was, without fresh data.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select f from FlightInstance f where f.id = :id")
	Optional<FlightInstance> findByIdForUpdate(@Param("id") Long id);

	/** Flights on a route and date that have not departed yet. Served by idx_instance_search. */
	@Query("""
			select f from FlightInstance f
			where f.originCode = :origin
			  and f.destinationCode = :destination
			  and f.flightDate = :date
			  and f.departureAt > :now
			order by f.departureAt
			""")
	List<FlightInstance> search(@Param("origin") String origin, @Param("destination") String destination,
			@Param("date") LocalDate date, @Param("now") Instant now);

}
