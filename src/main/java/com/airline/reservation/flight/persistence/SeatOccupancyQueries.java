package com.airline.reservation.flight.persistence;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only questions about which seats are taken on a flight, answered from the booking tables.
 * <p>
 * Plain SQL on purpose: the booking feature depends on the flight feature, so flight code must not
 * import booking classes (that would be a package cycle).
 * <p>
 * A seat is taken while its booking_seat row is ACTIVE, or HELD with the hold not yet expired.
 * A HELD row whose hold has expired counts as free even before a write releases it
 * (expired holds are released lazily, on the next booking, confirmation or cancellation).
 */
@Repository
public class SeatOccupancyQueries {

	// status IN ('ACTIVE', 'HELD') matches the partial index uq_active_seat, which serves this query.
	private static final String TAKEN_SEATS_SQL = """
			SELECT bs.seat_number
			FROM booking_seat bs
			JOIN booking b ON b.id = bs.booking_id
			WHERE bs.flight_instance_id = :instanceId
			  AND bs.status IN ('ACTIVE', 'HELD')
			  AND (bs.status = 'ACTIVE' OR b.hold_expires_at > :now)
			""";

	private static final String SEATS_ON_OVERDUE_HOLDS_SQL = """
			SELECT bs.flight_instance_id, COUNT(*) AS seats
			FROM booking_seat bs
			JOIN booking b ON b.id = bs.booking_id
			WHERE bs.flight_instance_id IN (:instanceIds)
			  AND bs.status = 'HELD'
			  AND b.hold_expires_at <= :now
			GROUP BY bs.flight_instance_id
			""";

	private final NamedParameterJdbcTemplate jdbc;

	public SeatOccupancyQueries(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Seat numbers that are taken on the flight at {@code now}. */
	public Set<String> takenSeats(long instanceId, Instant now) {
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("instanceId", instanceId)
				.addValue("now", now.atOffset(ZoneOffset.UTC));
		return new HashSet<>(jdbc.queryForList(TAKEN_SEATS_SQL, params, String.class));
	}

	/**
	 * Per flight, the number of seats still marked HELD although their hold has expired. These
	 * are free in reality but not yet added back to the available_seats counter.
	 * Flights with none are absent from the map.
	 */
	public Map<Long, Integer> seatsOnOverdueHolds(Collection<Long> instanceIds, Instant now) {
		Map<Long, Integer> seats = new HashMap<>();
		if (instanceIds.isEmpty()) {
			return seats;
		}
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("instanceIds", instanceIds)
				.addValue("now", now.atOffset(ZoneOffset.UTC));
		jdbc.query(SEATS_ON_OVERDUE_HOLDS_SQL, params,
				row -> {
					seats.put(row.getLong("flight_instance_id"), row.getInt("seats"));
				});
		return seats;
	}

}
