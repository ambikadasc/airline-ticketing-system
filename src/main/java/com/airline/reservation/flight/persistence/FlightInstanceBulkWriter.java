package com.airline.reservation.flight.persistence;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.airline.reservation.flight.domain.FlightInstance;

/**
 * Inserts generated flight instances in one JDBC batch. A year of a daily flight is ~366 rows,
 * so JPA's one-insert-per-entity would be wasteful here.
 */
@Repository
public class FlightInstanceBulkWriter {

	// ON CONFLICT ... DO NOTHING makes the insert idempotent: dates that already have an instance
	// for the schedule are skipped, so the window job can run any number of times.
	// PostgreSQL-specific. MySQL equivalent: INSERT IGNORE (or ON DUPLICATE KEY UPDATE id = id).
	private static final String INSERT_SQL = """
			INSERT INTO flight_instance (schedule_id, flight_number, origin_code, destination_code, aircraft_id,
			                             flight_date, departure_at, arrival_at, total_seats, available_seats)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT (schedule_id, flight_date) DO NOTHING
			""";

	private final JdbcTemplate jdbcTemplate;

	public FlightInstanceBulkWriter(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/** Returns the number of rows actually inserted (existing dates count as 0). */
	public int insertMissing(List<FlightInstance> instances) {
		int[][] counts = jdbcTemplate.batchUpdate(INSERT_SQL, instances, 500, FlightInstanceBulkWriter::bind);
		return Arrays.stream(counts).flatMapToInt(Arrays::stream).sum();
	}

	private static void bind(PreparedStatement statement, FlightInstance instance) throws SQLException {
		statement.setLong(1, instance.getScheduleId());
		statement.setString(2, instance.getFlightNumber());
		statement.setString(3, instance.getOriginCode());
		statement.setString(4, instance.getDestinationCode());
		statement.setLong(5, instance.getAircraftId());
		statement.setObject(6, instance.getFlightDate());
		statement.setObject(7, instance.getDepartureAt().atOffset(ZoneOffset.UTC));
		statement.setObject(8, instance.getArrivalAt().atOffset(ZoneOffset.UTC));
		statement.setInt(9, instance.getTotalSeats());
		statement.setInt(10, instance.getAvailableSeats());
	}

}
