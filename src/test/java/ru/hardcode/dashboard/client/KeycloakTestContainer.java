package ru.hardcode.dashboard.client;

import java.util.List;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.utility.DockerImageName;

/**
 * Real Keycloak for integration tests. It imports the same realm fixture that {@code compose.yaml}
 * renders (copied to the test classpath by Gradle). The fixture is not a template, so the
 * clearly labelled test credentials are applied to it through the Keycloak Admin API, exactly like
 * an administrator pre-creating accounts.
 *
 * <p>The issuer Keycloak asserts is published as {@link #ISSUER_PROPERTY} before the Spring
 * context starts, so the decoder uses the issuer of the container itself.
 */
@TestConfiguration(proxyBeanMethods = false)
public class KeycloakTestContainer {

	public static final String IMAGE = "quay.io/keycloak/keycloak:26.4.7";
	public static final String REALM = "dashboard";
	public static final String ISSUER_PROPERTY = "keycloak.issuer";
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

	/**
	 * Sets the test credentials on the imported fixture. Users and client secrets in the fixture
	 * are not real credentials, so nothing secret is committed.
	 */
	static void applyTestCredentials(KeycloakContainer container) {
		KeycloakAdminTestClient admin = new KeycloakAdminTestClient(issuer(container), ADMIN_USERNAME, ADMIN_PASSWORD);
		admin.resetPassword(TEST_USERNAME, TEST_PASSWORD);
		admin.resetClientSecret(MACHINE_CLIENT_ID, ITSM_SENDER_SECRET);
	}

	private static KeycloakContainer start() {
		KeycloakContainer container = new KeycloakContainer(
				DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("quay.io/keycloak/keycloak"))
				.withRealmImportFile("keycloak/realm/dashboard-realm.json")
				.withAdminUsername(ADMIN_USERNAME)
				.withAdminPassword(ADMIN_PASSWORD);
		container.start();
		applyTestCredentials(container);
		System.setProperty(ISSUER_PROPERTY, issuer(container));
		return container;
	}

	private static final class Holder {
		static final KeycloakContainer KEYCLOAK = start();
	}

	@Bean
	@ServiceConnection
	KeycloakContainer keycloakContainer() {
		return keycloak();
	}
}
