package com.airline.reservation.schedule.service;

import java.time.LocalDate;

/**
 * Outcome of topping up the flight-instance window: how many instances were missing and inserted,
 * and the last date the window now reaches (today + airline.booking-window-days).
 */
public record InstanceWindowResult(int inserted, LocalDate windowEnd) {
}
