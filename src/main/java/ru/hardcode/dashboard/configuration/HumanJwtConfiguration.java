package ru.hardcode.dashboard.configuration;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.util.StringUtils;

/**
 * Decoder for human Keycloak tokens: signature via JWKS, exact issuer, the human audience and a
 * mandatory expiry ({@code exp}/{@code nbf}) with the configured clock skew. Without
 * configuration every token is rejected, so protected routes stay fail-closed while health and
 * the demo keep working.
 */
@Configuration(proxyBeanMethods = false)
public class HumanJwtConfiguration {

	private static final Logger LOG = LoggerFactory.getLogger(HumanJwtConfiguration.class);

	@Bean
	public HumanDecoder humanJwtDecoder(HumanAuthProperties properties) {
		if (!properties.isConfigured()) {
			LOG.warn("Human authentication is not configured; the user identity endpoint rejects all bearer tokens");
			return new HumanDecoder(token -> {
				throw new BadJwtException("Human authentication is not configured");
			});
		}

		NimbusJwtDecoder decoder = StringUtils.hasText(properties.jwkSetUri())
				? NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build()
				: NimbusJwtDecoder.withIssuerLocation(properties.issuerUri()).build();
		// Only these validators run: Spring's default set adds a second timestamp validator with
		// its own 60-second skew and does not require exp, so it is deliberately not used here.
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(List.of(
				new JwtIssuerValidator(properties.issuer()),
				new JwtClaimValidator<Object>("sub", value -> value instanceof String sub && !sub.isBlank()),
				new JwtAudienceValidator(properties.audience()),
				new JwtExpiryValidator(properties.clockSkew())
		)));
		return new HumanDecoder(decoder);
	}

	// Keep the human decoder local to the human chain so Boot does not configure its own.
	public record HumanDecoder(JwtDecoder decoder) {
	}
}
