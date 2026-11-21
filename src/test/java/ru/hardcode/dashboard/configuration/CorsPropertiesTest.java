package ru.hardcode.dashboard.configuration;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CorsPropertiesTest {

	@Test
	void unsetOrigins_defaultToTheSpaDevOrigins() {
		assertThat(new CorsProperties(null).allowedOrigins())
				.containsExactly("http://localhost:3000", "http://localhost:5173");
	}

	@Test
	void configuredOrigins_areKeptAfterTrimming() {
		assertThat(new CorsProperties(List.of(" https://stand.mts.example ", "http://localhost:5173")).allowedOrigins())
				.containsExactly("https://stand.mts.example", "http://localhost:5173");
	}

	@Test
	void trailingSlashes_areStrippedBecauseOriginsNeverHaveThem() {
		assertThat(new CorsProperties(List.of("https://stand.mts.example/", "http://host//")).allowedOrigins())
				.containsExactly("https://stand.mts.example", "http://host");
	}

	@Test
	void blankEntries_areDropped() {
		assertThat(new CorsProperties(java.util.Arrays.asList("  ", "https://stand.mts.example", null)).allowedOrigins())
				.containsExactly("https://stand.mts.example");
	}

	@Test
	void explicitlyEmptyConfiguration_disablesCors() {
		// Fail-closed: no origin is allowed, so no browser receives CORS headers.
		assertThat(new CorsProperties(List.of()).allowedOrigins()).isEmpty();
		assertThat(new CorsProperties(List.of("  ")).allowedOrigins()).isEmpty();
	}
}
