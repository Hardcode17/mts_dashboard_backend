package ru.hardcode.dashboard.client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * Real Keycloak for integration tests. It renders the same realm template as {@code compose.auth.yaml}
 * with disposable test values and imports it before starting the realm.
 *
 * <p>Spring tests use the issuer of the container itself.
 */
@TestConfiguration(proxyBeanMethods = false)
public class KeycloakTestContainer {

	public static final String IMAGE = "quay.io/keycloak/keycloak:26.4.7";
	public static final String REALM = "dashboard";
	public static final String ADMIN_USERNAME = "kan16-admin";
	public static final String ADMIN_PASSWORD = "kan16-local-admin-test-password";

	/** Pre-created human account of the realm fixture. */
	public static final String TEST_USERNAME = "kan16-test-user";
	public static final String TEST_EMAIL = "kan16-test-user@example.test";
	public static final String TEST_PASSWORD = "kan16-local-fixture-test-password";

	/** Account created by the administrator only for the integration test. */
	public static final String ADMIN_CREATED_USERNAME = "kan16-admin-created-user";
	public static final String ADMIN_CREATED_EMAIL = "kan16-admin-created@example.test";
	public static final String ADMIN_CREATED_PASSWORD = "kan16-local-admin-created-password";

	public static final String ITSM_SENDER_SECRET = "kan16-local-itsm-sender-test-secret";

	public static final String HUMAN_CLIENT_ID = "dashboard-web";
	public static final String HUMAN_AUDIENCE = "dashboard-api";
	public static final String MACHINE_CLIENT_ID = "itsm-sender";
	public static final String MACHINE_AUDIENCE = "itsm-ingest";
	public static final String IMPORT_ROLE = "itsm:import";

	public static final List<String> DASHBOARD_USERS_GROUP = List.of("/dashboard-users");

	private KeycloakTestContainer() {
	}

	/** The Keycloak shared by all ITs of this JVM; started on first use. */
	public static KeycloakContainer keycloak() {
		return Holder.KEYCLOAK;
	}

	public static String issuer() {
		return issuer(keycloak());
	}

	public static String issuer(KeycloakContainer container) {
		return container.getAuthServerUrl().replaceAll("/+$", "") + "/realms/" + REALM;
	}

	public static String jwkSetUri() {
		return issuer() + "/protocol/openid-connect/certs";
	}

	private static KeycloakContainer start() {
		KeycloakContainer container = new KeycloakContainer(
				DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("quay.io/keycloak/keycloak"))
				.withCopyToContainer(Transferable.of(renderRealm()), "/opt/keycloak/data/import/dashboard-realm.json")
				.withAdminUsername(ADMIN_USERNAME)
				.withAdminPassword(ADMIN_PASSWORD);
		container.start();
		return container;
	}

	private static String renderRealm() {
		try (var template = KeycloakTestContainer.class.getResourceAsStream("/keycloak/realm/dashboard-realm.json")) {
			if (template == null) {
				throw new IllegalStateException("Missing dashboard realm template");
			}
			return new String(template.readAllBytes(), StandardCharsets.UTF_8)
					.replace("__DASHBOARD_TEST_USERNAME__", TEST_USERNAME)
					.replace("__DASHBOARD_TEST_EMAIL__", TEST_EMAIL)
					.replace("__DASHBOARD_TEST_PASSWORD__", TEST_PASSWORD)
					.replace("__ITSM_SENDER_CLIENT_SECRET__", ITSM_SENDER_SECRET)
					.replace("__DASHBOARD_WEB_ORIGIN__", "http://localhost:3000");
		} catch (IOException exception) {
			throw new UncheckedIOException(exception);
		}
	}

	private static final class Holder {
		static final KeycloakContainer KEYCLOAK = start();
	}

	@Bean
	KeycloakContainer keycloakContainer() {
		return keycloak();
	}
}
