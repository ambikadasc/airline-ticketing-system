package com.airline.reservation.schedule.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;

/**
 * A flight schedule: the template from which dated flight instances are generated.
 * Schedules never change after creation, so there are no setters.
 * <p>
 * Times are UTC times of day. The arrival day offset is derived, never supplied: an arrival at
 * or before the departure time means the flight lands the next day.
 */
@Entity
public class FlightSchedule {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String flightNumber;

	private String originCode;

	private String destinationCode;

	private LocalTime departureTime;

	private LocalTime arrivalTime;

	private int arrivalDayOffset;

	private Long aircraftId;

	private Instant createdAt;

	@ElementCollection
	@CollectionTable(name = "flight_schedule_day", joinColumns = @JoinColumn(name = "schedule_id"))
	@Column(name = "day_of_week")
	@Enumerated(EnumType.STRING)
	private Set<DayOfWeek> daysOfOperation = new HashSet<>();

	protected FlightSchedule() {
		// for JPA
	}

	public static FlightSchedule create(String flightNumber, String originCode, String destinationCode,
			LocalTime departureTime, LocalTime arrivalTime, Long aircraftId, Set<DayOfWeek> daysOfOperation,
			Instant createdAt) {
		FlightSchedule schedule = new FlightSchedule();
		schedule.flightNumber = flightNumber;
		schedule.originCode = originCode;
		schedule.destinationCode = destinationCode;
		schedule.departureTime = departureTime;
		schedule.arrivalTime = arrivalTime;
		schedule.arrivalDayOffset = arrivalTime.isAfter(departureTime) ? 0 : 1;
		schedule.aircraftId = aircraftId;
		schedule.daysOfOperation = EnumSet.copyOf(daysOfOperation);
		schedule.createdAt = createdAt;
		return schedule;
	}

	public boolean operatesOn(DayOfWeek day) {
		return daysOfOperation.contains(day);
	}

	public Long getId() {
		return id;
	}

	public String getFlightNumber() {
		return flightNumber;
	}

	public String getOriginCode() {
		return originCode;
	}

	public String getDestinationCode() {
		return destinationCode;
	}

	public LocalTime getDepartureTime() {
		return departureTime;
	}

	public LocalTime getArrivalTime() {
		return arrivalTime;
	}

	public int getArrivalDayOffset() {
		return arrivalDayOffset;
	}

	public Long getAircraftId() {
		return aircraftId;
	}

	public Set<DayOfWeek> getDaysOfOperation() {
		return Collections.unmodifiableSet(daysOfOperation);
	}

}
