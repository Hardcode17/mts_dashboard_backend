package ru.hardcode.dashboard.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpServer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Local JWKS endpoint for the KAN-16 integration tests. A real Keycloak issues only valid
 * tokens, so these tests point another decoder at this endpoint, serve the public key of
 * {@link TestJwtSigner} and demand the issuer Keycloak actually asserts. That reproduces the
 * chained resource-server filter chain exactly (JWKS fetch, signature, issuer, audience, exp)
 * without leaving the test process.
 */
@TestConfiguration(proxyBeanMethods = false)
public class Kan16JwtTestConfiguration {

	private static final String JWKS_PATH = "/realms/dashboard/protocol/openid-connect/certs";

	private static volatile TestJwtSigner signer;
	private static volatile HttpServer server;
	private static volatile String jwkSetUri;

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
					jwkSetUri = start();
				}
			}
		}
		return jwkSetUri;
	}

	private static String start() {
		try {
			HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			httpServer.createContext(JWKS_PATH, exchange -> {
				byte[] body = signer().publicJwkSet().getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, body.length);
				try (var stream = exchange.getResponseBody()) {
					stream.write(body);
				}
			});
			httpServer.start();
			server = httpServer;
			Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
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
