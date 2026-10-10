package com.airline.reservation.booking.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.airline.reservation.booking.domain.Booking;

public interface BookingRepository extends JpaRepository<Booking, Long> {

	boolean existsByReference(String reference);

	/**
	 * Only the flight id, not the booking entity. Cancellation needs the flight id to take the flight
	 * lock, and must not load the booking before that lock (it would be a stale copy).
	 */
	@Query("select b.flightInstanceId from Booking b where b.reference = :reference")
	Optional<Long> findFlightInstanceIdByReference(@Param("reference") String reference);

	/** HELD bookings on one flight whose hold has expired, with their seats. Served by idx_booking_instance. */
	@Query("""
			select distinct b from Booking b join fetch b.seats
			where b.flightInstanceId = :flightInstanceId
			  and b.status = com.airline.reservation.booking.domain.BookingStatus.HELD
			  and b.holdExpiresAt <= :now
			""")
	List<Booking> findOverdueHolds(@Param("flightInstanceId") Long flightInstanceId, @Param("now") Instant now);

	/** The booking and its seats in one query. Served by the unique index on reference. */
	@Query("select b from Booking b join fetch b.seats where b.reference = :reference")
	Optional<Booking> findWithSeatsByReference(@Param("reference") String reference);

	/** The booking a client's Idempotency-Key created, if any. Served by uq_booking_idempotency_key. */
	@Query("select b from Booking b join fetch b.seats where b.idempotencyKey = :key")
	Optional<Booking> findWithSeatsByIdempotencyKey(@Param("key") String key);

}
