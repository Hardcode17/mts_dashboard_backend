package ru.hardcode.dashboard.configuration;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;

import static org.assertj.core.api.Assertions.assertThat;

class JwtAudienceValidatorTest {

	@Test
	void acceptsRequiredAudienceAmongSeveral() {
		assertThat(new JwtAudienceValidator("dashboard-api").validate(jwt(List.of("account", "dashboard-api")))
				.hasErrors()).isFalse();
	}

	@Test
	void rejectsTokenIssuedForAnotherAudience() {
		assertThat(new JwtAudienceValidator("dashboard-api").validate(jwt(List.of("account", "itsm-ingest")))
				.hasErrors()).isTrue();
	}

	@Test
	void expiryAllowsConfiguredSkewOnBothSides() {
		JwtExpiryValidator validator = new JwtExpiryValidator(Duration.ofSeconds(30));

		assertThat(validator.validate(expiringIn(Duration.ofSeconds(-29))).hasErrors()).isFalse();
		assertThat(validator.validate(expiringIn(Duration.ofSeconds(-31))).hasErrors()).isTrue();
		assertThat(validator.validate(expiringIn(Duration.ofSeconds(300))).hasErrors()).isFalse();
	}

	@Test
	void expiryBeyondMinuteIsNotLimitedToSixtySeconds() {
		JwtExpiryValidator validator = new JwtExpiryValidator(Duration.ofSeconds(90));

		assertThat(validator.validate(expiringIn(Duration.ofSeconds(-80))).hasErrors()).isFalse();
		assertThat(validator.validate(expiringIn(Duration.ofSeconds(-100))).hasErrors()).isTrue();
	}

	@Test
	void expiryIsRequired() {
		Jwt withoutExp = Jwt.withTokenValue("token")
				.header("alg", "RS256")
				.claim(JwtClaimNames.SUB, "user")
				.issuedAt(Instant.now())
				.build();

		assertThat(new JwtExpiryValidator(Duration.ofSeconds(30)).validate(withoutExp).hasErrors()).isTrue();
	}

	private static Jwt expiringIn(Duration offset) {
		Instant now = Instant.now();
		return Jwt.withTokenValue("token")
				.header("alg", "RS256")
				.claim(JwtClaimNames.SUB, "user")
				.issuedAt(now.minusSeconds(600))
				.expiresAt(now.plus(offset))
				.build();
	}

	private static Jwt jwt(List<String> audience) {
		return Jwt.withTokenValue("token")
				.header("alg", "RS256")
				.claim(JwtClaimNames.SUB, "user")
				.audience(audience)
				.build();
	}
}
