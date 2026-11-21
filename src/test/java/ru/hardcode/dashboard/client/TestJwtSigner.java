package ru.hardcode.dashboard.client;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Signs tokens locally for the negative and boundary cases that a real Keycloak cannot produce
 * on request: a foreign signing key, expiry outside the skew, other issuers and audiences. The
 * JWKS endpoint served from this key is only reachable through the test server; a real
 * Keycloak's tokens never validate against it.
 */
public final class TestJwtSigner {

	public static final String KID = "kan16-test-key";

	private final RSAKey rsaKey = generate();
	private final JWSSigner signer;

	public TestJwtSigner() {
		try {
			this.signer = new RSASSASigner(rsaKey);
		} catch (JOSEException exception) {
			throw new IllegalStateException(exception);
		}
	}

	/** The public half of the key; the private half of a foreign key is never published. */
	public String publicJwkSet() {
		RSAKey publicKey = rsaKey.toPublicJWK();
		return new JWKSet(publicKey).toString();
	}

	public String token(Claims claims) {
		JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
				.subject(claims.subject())
				.issueTime(Date.from(claims.issuedAt()))
				.claim("preferred_username", claims.subject());
		if (claims.issuer() != null) {
			builder.issuer(claims.issuer());
		}
		if (claims.audience() != null) {
			builder.audience(claims.audience());
		}
		if (claims.expiresAt() != null) {
			builder.expirationTime(Date.from(claims.expiresAt()));
		}
		claims.extraClaims().forEach(builder::claim);
		JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KID).build();
		SignedJWT jwt = new SignedJWT(header, builder.build());
		try {
			jwt.sign(signer);
		} catch (JOSEException exception) {
			throw new IllegalStateException(exception);
		}
		return jwt.serialize();
	}

	public static Claims claims() {
		return new Claims();
	}

	/** One independent key, as a foreign issuer or attacker would have. */
	public static String foreignToken(String issuer, String audience, Duration expiresIn) {
		TestJwtSigner foreign = new TestJwtSigner();
		Instant now = Instant.now();
		return foreign.token(claims()
				.issuer(issuer)
				.subject(TestJwtSigner.class.getSimpleName().toLowerCase() + "-foreign")
				.audience(audience)
				.issuedAt(now.minusSeconds(60))
				.expiresAt(now.plus(expiresIn)));
	}

	private static RSAKey generate() {
		try {
			return new RSAKeyGenerator(2048).keyID(KID).generate();
		} catch (JOSEException exception) {
			throw new IllegalStateException(exception);
		}
	}

	/** Fluent claim set; only the claims explicitly set are included. */
	public static final class Claims {

		private final Map<String, Object> extraClaims = new LinkedHashMap<>();
		private String subject = "kan16-signed-subject";
		private String issuer;
		private List<String> audience;
		private Instant issuedAt = Instant.now().minusSeconds(60);
		private Instant expiresAt;

		public Claims issuer(String value) {
			this.issuer = value;
			return this;
		}

		public Claims subject(String value) {
			this.subject = value;
			return this;
		}

		public Claims audience(String... values) {
			this.audience = List.of(values);
			return this;
		}

		public Claims issuedAt(Instant value) {
			this.issuedAt = value;
			return this;
		}

		public Claims expiresAt(Instant value) {
			this.expiresAt = value;
			return this;
		}

		public Claims claim(String name, Object value) {
			this.extraClaims.put(name, value);
			return this;
		}

		String subject() {
			return subject;
		}

		String issuer() {
			return issuer;
		}

		List<String> audience() {
			return audience;
		}

		Instant issuedAt() {
			return issuedAt;
		}

		Instant expiresAt() {
			return expiresAt;
		}

		Map<String, Object> extraClaims() {
			return extraClaims;
		}
	}
}
