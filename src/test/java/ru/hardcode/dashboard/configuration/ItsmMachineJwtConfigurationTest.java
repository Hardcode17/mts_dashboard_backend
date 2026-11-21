package ru.hardcode.dashboard.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.BadJwtException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ItsmMachineJwtConfigurationTest {

	@Test
	void unconfiguredIntegrationAuthRejectsEveryToken() {
		ItsmMachineJwtConfiguration configuration = new ItsmMachineJwtConfiguration();
		JwtDecoder decoder = configuration.itsmMachineJwtDecoder(
				new ItsmIntegrationAuthProperties("", "", "", "", "")
		).decoder();

		assertThatThrownBy(() -> decoder.decode("any-token"))
				.isInstanceOf(BadJwtException.class)
				.hasMessage("ITSM machine authentication is not configured");
	}
}
