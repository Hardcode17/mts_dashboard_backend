package ru.hardcode.dashboard.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.ArrayList;

import com.nimbusds.jose.jwk.JWKSet;
import com.sun.net.httpserver.HttpServer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Local JWKS endpoints for integration tests. The public test key allows a real decoder to
 * reach claim validation for cases that Keycloak will not issue itself. For KAN-16 the test
 * key is served alongside the real container's public keys; the machine chain uses Keycloak directly.
 */
@TestConfiguration(proxyBeanMethods = false)
public class Kan16JwtTestConfiguration {

	private static final String JWKS_PATH = "/realms/dashboard/protocol/openid-connect/certs";

	private static volatile TestJwtSigner signer;
	private static volatile String jwkSetUri;
	private static volatile String combinedJwkSetUri;

	public static TestJwtSigner signer() {
		if (signer == null) {
			synchronized (Kan16JwtTestConfiguration.class) {
				if (signer == null) {
					signer = new TestJwtSigner();
				}
			}
		}
		return signer;
	}

	/** URL of the JWKS document; the server is started on first use. */
	public static String jwkSetUri() {
		if (jwkSetUri == null) {
			synchronized (Kan16JwtTestConfiguration.class) {
				if (jwkSetUri == null) {
					jwkSetUri = serve(signer().publicJwkSet());
				}
			}
		}
		return jwkSetUri;
	}

	public static synchronized String combinedJwkSetUri() {
		if (combinedJwkSetUri == null) {
			try {
				var realKeys = JWKSet.load(URI.create(KeycloakTestContainer.jwkSetUri()).toURL(), 5_000, 5_000, 100_000);
				var keys = new ArrayList<>(realKeys.getKeys());
				keys.addAll(JWKSet.parse(signer().publicJwkSet()).getKeys());
				combinedJwkSetUri = serve(new JWKSet(keys).toString());
			} catch (IOException | ParseException exception) {
				throw new IllegalStateException("Cannot read public keys from the Keycloak test container", exception);
			}
		}
		return combinedJwkSetUri;
	}

	private static String serve(String jwkSet) {
		try {
			HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			httpServer.createContext(JWKS_PATH, exchange -> {
				byte[] body = jwkSet.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, body.length);
				try (var stream = exchange.getResponseBody()) {
					stream.write(body);
				}
			});
			httpServer.start();
			Runtime.getRuntime().addShutdownHook(new Thread(() -> httpServer.stop(0)));
			return "http://127.0.0.1:" + httpServer.getAddress().getPort() + JWKS_PATH;
		} catch (IOException exception) {
			throw new IllegalStateException("Cannot start the local JWKS server", exception);
		}
	}

	@Bean
	Kan16SignedTokens kan16SignedTokens() {
		return new Kan16SignedTokens(signer());
	}

	/** Injectable signer for tests. */
	public record Kan16SignedTokens(TestJwtSigner signer) {
	}
}
