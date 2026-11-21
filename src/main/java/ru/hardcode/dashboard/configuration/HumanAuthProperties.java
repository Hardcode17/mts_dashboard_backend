package ru.hardcode.dashboard.configuration;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * Keycloak settings for human users of the dashboard. The issuer must be identical for the
 * browser and the backend; {@code jwkSetUri} may use an address only the backend can reach.
 * {@code issuerUri} is only used to discover the JWKS endpoint when {@code jwkSetUri} is blank.
 */
@ConfigurationProperties(prefix = "app.auth.human")
public record HumanAuthProperties(
		String issuer,
		String issuerUri,
		String jwkSetUri,
		String audience,
		Duration clockSkew
) {

	public static final Duration DEFAULT_CLOCK_SKEW = Duration.ofSeconds(30);

	public HumanAuthProperties {
		clockSkew = clockSkew == null ? DEFAULT_CLOCK_SKEW : clockSkew;
		if (clockSkew.isNegative()) {
			throw new IllegalArgumentException("app.auth.human.clock-skew must not be negative");
		}
	}

	public boolean isRequested() {
		return StringUtils.hasText(issuer) || StringUtils.hasText(audience)
				|| StringUtils.hasText(jwkSetUri) || StringUtils.hasText(issuerUri);
	}

	public boolean isConfigured() {
		return StringUtils.hasText(issuer)
				&& StringUtils.hasText(audience)
				&& (StringUtils.hasText(jwkSetUri) || StringUtils.hasText(issuerUri));
	}
}
