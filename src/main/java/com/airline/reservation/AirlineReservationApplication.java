package com.airline.reservation;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AirlineReservationApplication {

	public static void main(String[] args) {
		// All times are UTC; this stops LocalTime and TIME columns shifting on a non-UTC host.
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(AirlineReservationApplication.class, args);
	}

}
