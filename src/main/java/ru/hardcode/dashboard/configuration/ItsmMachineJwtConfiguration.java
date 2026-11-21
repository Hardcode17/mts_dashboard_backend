package ru.hardcode.dashboard.configuration;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Builds a Keycloak JWT decoder with both issuer and audience validation. When the
 * integration auth settings are absent the decoder rejects every token so the routes
 * stay fail-closed while the demo deployment can still start.
 */
@Configuration(proxyBeanMethods = false)
public class ItsmMachineJwtConfiguration {

	private static final Logger LOG = LoggerFactory.getLogger(ItsmMachineJwtConfiguration.class);

	@Bean
	public MachineDecoder itsmMachineJwtDecoder(ItsmIntegrationAuthProperties properties) {
		if (!properties.isConfigured()) {
			LOG.warn("ITSM machine authentication is not configured; integration routes reject all bearer tokens");
			return new MachineDecoder(token -> {
				throw new BadJwtException("ITSM machine authentication is not configured");
			});
		}

		NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
		List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
		validators.add(JwtValidators.createDefaultWithIssuer(properties.issuer()));
		validators.add(audienceValidator(properties.audience()));
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
		return new MachineDecoder(decoder);
	}

	private static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
		return jwt -> jwt.getAudience() != null && jwt.getAudience().contains(audience)
				? OAuth2TokenValidatorResult.success()
				: OAuth2TokenValidatorResult.failure(new OAuth2Error(
						"invalid_token",
						"The required audience is missing",
						null
				));
	}

	// Keep the machine decoder local to this chain so Boot still configures dashboard login.
	public record MachineDecoder(JwtDecoder decoder) {
	}
}
