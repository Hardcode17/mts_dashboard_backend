package ru.hardcode.dashboard.configuration;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;

/**
 * Requires the configured audience to be one of the token audiences. Human and machine chains
 * each configure a different audience, so a token issued for one of them never satisfies the
 * other.
 */
public final class JwtAudienceValidator implements OAuth2TokenValidator<Jwt> {

	private final String audience;

	public JwtAudienceValidator(String audience) {
		if (!StringUtils.hasText(audience)) {
			throw new IllegalArgumentException("JWT audience must not be blank");
		}
		this.audience = audience;
	}

	@Override
	public OAuth2TokenValidatorResult validate(Jwt jwt) {
		return jwt.getAudience() != null && jwt.getAudience().contains(audience)
				? OAuth2TokenValidatorResult.success()
				: OAuth2TokenValidatorResult.failure(new OAuth2Error(
						OAuth2ErrorCodes.INVALID_TOKEN,
						"The required audience is missing",
						null
				));
	}
}
