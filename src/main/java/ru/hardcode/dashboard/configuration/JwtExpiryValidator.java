package ru.hardcode.dashboard.configuration;

import java.time.Duration;
import java.time.Instant;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Expiry of access tokens with exactly the configured clock skew. Unlike
 * {@code JwtTimestampValidator} defaults it requires {@code exp}: a token without an expiry is
 * rejected, and the accepted skew is the project setting, with no stricter value added elsewhere.
 */
public final class JwtExpiryValidator implements OAuth2TokenValidator<Jwt> {

	private final Duration clockSkew;

	public JwtExpiryValidator(Duration clockSkew) {
		this.clockSkew = clockSkew;
	}

	@Override
	public OAuth2TokenValidatorResult validate(Jwt jwt) {
		Instant now = Instant.now();
		Instant expiresAt = jwt.getExpiresAt();
		if (expiresAt == null) {
			return failure("An access token must contain exp");
		}
		if (now.isAfter(expiresAt.plus(clockSkew))) {
			return failure("The access token expired");
		}
		if (jwt.getNotBefore() != null && isTooFarInFuture(now, jwt.getNotBefore())) {
			return failure("The access token is not valid yet");
		}
		if (jwt.getIssuedAt() != null && isTooFarInFuture(now, jwt.getIssuedAt())) {
			return failure("The access token was issued in the future");
		}
		return OAuth2TokenValidatorResult.success();
	}

	private boolean isTooFarInFuture(Instant now, Instant instant) {
		return now.plus(clockSkew).isBefore(instant);
	}

	private static OAuth2TokenValidatorResult failure(String description) {
		return OAuth2TokenValidatorResult.failure(
				new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, description, null));
	}
}
