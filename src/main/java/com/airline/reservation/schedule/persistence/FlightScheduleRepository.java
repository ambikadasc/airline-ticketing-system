package com.airline.reservation.schedule.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.airline.reservation.schedule.domain.FlightSchedule;

public interface FlightScheduleRepository extends JpaRepository<FlightSchedule, Long> {

	boolean existsByFlightNumber(String flightNumber);

	/** Every schedule with its operating days in one query (avoids one query per schedule). */
	@Query("select distinct s from FlightSchedule s left join fetch s.daysOfOperation")
	List<FlightSchedule> findAllWithDays();

}
