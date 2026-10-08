package com.airline.reservation.flight.api;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.airline.reservation.flight.service.FlightSearchResult;
import com.airline.reservation.flight.service.FlightSearchService;
import com.airline.reservation.flight.service.SeatMapResult;
import com.airline.reservation.flight.service.SeatMapService;

@Tag(name = "Flights")
@RestController
@RequestMapping("/api/v1/flights")
public class FlightController {

	private final FlightSearchService searchService;
	private final SeatMapService seatMapService;

	public FlightController(FlightSearchService searchService, SeatMapService seatMapService) {
		this.searchService = searchService;
		this.seatMapService = seatMapService;
	}

	@Operation(summary = "Search flights by route and travel date (from today to the end of the booking window, airline.booking-window-days)")
	@GetMapping
	public List<FlightSearchResult> search(
			@Parameter(example = "DXB") @RequestParam @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter IATA code") String origin,
			@Parameter(example = "LHR") @RequestParam @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter IATA code") String destination,
			@Parameter(example = "2026-11-02") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		return searchService.search(origin, destination, date);
	}

	@Operation(summary = "Seat map of a flight: every seat with AVAILABLE or BOOKED")
	@GetMapping("/{flightInstanceId}/seats")
	public SeatMapResult seatMap(@PathVariable Long flightInstanceId) {
		return seatMapService.getSeatMap(flightInstanceId);
	}

}
