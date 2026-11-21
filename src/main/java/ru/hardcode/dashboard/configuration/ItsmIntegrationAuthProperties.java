package ru.hardcode.dashboard.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * Keycloak machine-client settings for the dedicated ITSM integration chain.
 */
@ConfigurationProperties(prefix = "itsm.integration.auth")
public record ItsmIntegrationAuthProperties(
		String issuer,
		String jwkSetUri,
		String audience,
		String clientId,
		String importRole
) {

	private static final String DEFAULT_IMPORT_ROLE = "itsm:import";

	public ItsmIntegrationAuthProperties {
		importRole = StringUtils.hasText(importRole) ? importRole : DEFAULT_IMPORT_ROLE;
	}

	public boolean isConfigured() {
		return StringUtils.hasText(issuer)
				&& StringUtils.hasText(jwkSetUri)
				&& StringUtils.hasText(audience)
				&& StringUtils.hasText(clientId);
	}
}
