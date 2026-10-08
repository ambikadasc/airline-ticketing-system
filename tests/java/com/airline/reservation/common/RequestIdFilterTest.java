package com.airline.reservation.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.airline.reservation.IntegrationTest;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class RequestIdFilterTest extends IntegrationTest {

	private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

	@Test
	void aSafeRequestIdFromTheCallerIsEchoedInTheHeaderAndTheErrorBody() throws Exception {
		mockMvc.perform(get("/api/v1/bookings/ZZZZZZ").header("X-Request-Id", "demo-123"))
				.andExpect(header().string("X-Request-Id", "demo-123"))
				.andExpect(jsonPath("$.requestId").value("demo-123"));
	}

	@Test
	void aMissingRequestIdIsGenerated() throws Exception {
		mockMvc.perform(get("/api/v1/bookings/ZZZZZZ"))
				.andExpect(header().string("X-Request-Id", matchesPattern(UUID)))
				.andExpect(jsonPath("$.requestId", matchesPattern(UUID)));
	}

	@ParameterizedTest
	@ValueSource(strings = {"evil\nINFO fake log line", "has space",
			"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
	void anUnsafeRequestIdIsReplaced(String unsafe) throws Exception {
		mockMvc.perform(get("/api/v1/bookings/ZZZZZZ").header("X-Request-Id", unsafe))
				.andExpect(header().string("X-Request-Id", matchesPattern(UUID)))
				.andExpect(header().string("X-Request-Id", not(unsafe)));
	}

}
