package ru.hardcode.dashboard.client;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import ru.hardcode.dashboard.configuration.ItsmMachineJwtConfiguration.MachineDecoder;

/**
 * Reusable signing material for Docker-free authentication tests. It mirrors the
 * production issuer/audience validation without contacting a real Keycloak instance.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ItsmMachineJwtTestConfiguration {

	public static final String ISSUER = "https://keycloak.test/realms/itsm";
	public static final String AUDIENCE = "itsm-ingest";
	public static final String CLIENT_ID = "itsm-machine";
	public static final String IMPORT_ROLE = "itsm:import";

	private static final KeyPair KEY_PAIR = createKeyPair();

	@Bean
	@Primary
	public JwtEncoder itsmTestJwtEncoder() {
		return NimbusJwtEncoder
				.withKeyPair((RSAPublicKey) KEY_PAIR.getPublic(), (RSAPrivateKey) KEY_PAIR.getPrivate())
				.algorithm(SignatureAlgorithm.RS256)
				.build();
	}

	@Bean
	@Primary
	public MachineDecoder itsmTestJwtDecoder() {
		NimbusJwtDecoder decoder = NimbusJwtDecoder
				.withPublicKey((RSAPublicKey) KEY_PAIR.getPublic())
				.build();
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
				JwtValidators.createDefaultWithIssuer(ISSUER),
				audienceValidator(AUDIENCE)
		));
		return new MachineDecoder(decoder);
	}

	public static String machineToken(JwtEncoder encoder) {
		return token(encoder, ISSUER, AUDIENCE, CLIENT_ID, List.of(IMPORT_ROLE), null);
	}

	public static String token(
			JwtEncoder encoder,
			String issuer,
			String audience,
			String azp,
			List<String> realmRoles,
			List<String> clientRoles
	) {
		Instant now = Instant.now();
		JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
				.issuer(issuer)
				.subject("service-account-" + azp)
				.audience(List.of(audience))
				.issuedAt(now)
				.expiresAt(now.plusSeconds(600))
				.claim("azp", azp);
		if (realmRoles != null) {
			claims.claim("realm_access", Map.of("roles", realmRoles));
		}
		if (clientRoles != null) {
			claims.claim("resource_access", Map.of(azp, Map.of("roles", clientRoles)));
		}
		return encoder.encode(JwtEncoderParameters.from(
				JwsHeader.with(SignatureAlgorithm.RS256).build(),
				claims.build()
		)).getTokenValue();
	}

	private static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
		return jwt -> jwt.getAudience().contains(audience)
				? OAuth2TokenValidatorResult.success()
				: OAuth2TokenValidatorResult.failure(new OAuth2Error(
						"invalid_token",
						"The required audience is missing",
						null
				));
	}

	private static KeyPair createKeyPair() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			return generator.generateKeyPair();
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("RSA is not available", exception);
		}
	}
}
