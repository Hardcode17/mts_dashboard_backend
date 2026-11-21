package ru.hardcode.dashboard;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.images.builder.Transferable;
import ru.hardcode.dashboard.configuration.CoordinatorAuthorizationManager;
import ru.hardcode.dashboard.configuration.CoordinatorSecurityErrors;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real Keycloak tokens and PostgreSQL; the test chain is the KAN-16 integration seam. */
@SpringBootTest(properties = {"coordinator.access.user-client-ids=dashboard-web", "itsm.worker.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("work")
@Import({ItsmPostgresTestcontainersConfiguration.class, 
		CoordinatorAccessIT.ProbeController.class})
@Testcontainers
class CoordinatorAccessIT {

	private static final String SUBJECT = "10000000-0000-0000-0000-000000000022";
	private static final ObjectMapper JSON = new ObjectMapper();

	@Container
	static final GenericContainer<?> keycloak = new GenericContainer<>("quay.io/keycloak/keycloak:26.2.5")
			.withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "it-admin")
			.withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "it-admin-password")
			.withExposedPorts(8080)
			.withCommand("start-dev", "--import-realm")
			.withCopyToContainer(Transferable.of(realm("coordinator-it")), "/opt/keycloak/data/import/coordinator-it-realm.json")
			.withCopyToContainer(Transferable.of(realm("other-it")), "/opt/keycloak/data/import/other-it-realm.json")
			.waitingFor(Wait.forHttp("/realms/coordinator-it/.well-known/openid-configuration").forStatusCode(200))
			.withStartupTimeout(Duration.ofMinutes(3));

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("app.auth.human.issuer", () -> issuer("coordinator-it"));
		registry.add("app.auth.human.audience", () -> "dashboard-api");
		registry.add("app.auth.human.jwk-set-uri", () -> issuer("coordinator-it") + "/protocol/openid-connect/certs");
		registry.add("itsm.integration.auth.issuer", () -> issuer("coordinator-it"));
		registry.add("itsm.integration.auth.jwk-set-uri", () -> issuer("coordinator-it") + "/protocol/openid-connect/certs");
		registry.add("itsm.integration.auth.audience", () -> "itsm-ingest");
		registry.add("itsm.integration.auth.client-id", () -> "itsm-machine");
	}

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	private long userId;
	private String token;

	@BeforeEach
	void seed() throws Exception {
		jdbc.update("DELETE FROM users");
		userId = jdbc.queryForObject("""
				INSERT INTO users(identity_issuer, identity_subject, login, display_name)
				VALUES (?, ?, 'coordinator', 'Test Coordinator') RETURNING id
				""", Long.class, issuer("coordinator-it"), SUBJECT);
		token = userToken("coordinator-it", "coordinator");
	}

	@Test
	void realJwtResolvesStableLocalProfileWithoutProvisioningOrUpdatingIt() throws Exception {
		mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.id").value(userId))
				.andExpect(jsonPath("$.sub").value(SUBJECT))
				.andExpect(jsonPath("$.displayName").value("Test Coordinator"));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isEqualTo(1);
	}

	@Test
	void changedTokenUsernameDoesNotChangeIdentity() throws Exception {
		assertThat(keycloak.execInContainer("/opt/keycloak/bin/kcadm.sh", "config", "credentials",
				"--server", "http://localhost:8080", "--realm", "master", "--user", "it-admin",
				"--password", "it-admin-password").getExitCode()).isZero();
		try {
			assertThat(keycloak.execInContainer("/opt/keycloak/bin/kcadm.sh", "update", "users/" + SUBJECT,
					"-r", "coordinator-it", "-s", "username=renamed-coordinator").getExitCode()).isZero();
			String renamed = userToken("coordinator-it", "renamed-coordinator");
			mvc.perform(get("/api/me").header("Authorization", "Bearer " + renamed))
					.andExpect(status().isOk()).andExpect(jsonPath("$.id").value(userId))
					.andExpect(jsonPath("$.login").value("coordinator"));
			assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isEqualTo(1);
		} finally {
			assertThat(keycloak.execInContainer("/opt/keycloak/bin/kcadm.sh", "update", "users/" + SUBJECT,
					"-r", "coordinator-it", "-s", "username=coordinator").getExitCode()).isZero();
		}
	}

	@Test
	void deactivationTakesEffectOnNextRequestWithSameTokenAndProtectsWrites() throws Exception {
		mvc.perform(post("/api/coordinator-test/write").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk());
		jdbc.update("UPDATE users SET is_active = false WHERE id = ?", userId);
		assertDenied(token);
		mvc.perform(post("/api/coordinator-test/write").header("Authorization", "Bearer " + token))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COORDINATOR_ACCESS_DENIED"));
		assertThat(jdbc.queryForObject("SELECT display_name FROM users WHERE id = ?", String.class, userId))
				.isEqualTo("write-1");
	}

	@Test
	void unknownSubjectCannotBorrowMatchingLoginAndNoUserIsCreated() throws Exception {
		// Existing login matches the token, but the trusted subject does not.
		jdbc.update("UPDATE users SET identity_subject = 'external-employee' WHERE id = ?", userId);
		assertDenied(token);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isEqualTo(1);
	}

	@Test
	void sameSubjectAndLoginInAnotherTrustedIssuerCannotBorrowProfile() throws Exception {
		jdbc.update("UPDATE users SET identity_issuer = ? WHERE id = ?", issuer("other-it"), userId);
		assertDenied(token);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isEqualTo(1);
	}

	@Test
	void sameLoginWithDifferentSubjectsIsAllowedBySchemaButNotInterchangeable() throws Exception {
		long other = jdbc.queryForObject("""
				INSERT INTO users(identity_issuer, identity_subject, login, display_name)
				VALUES (?, 'another-subject', 'coordinator', 'Other') RETURNING id
				""", Long.class, issuer("coordinator-it"));
		assertThat(other).isNotEqualTo(userId);
		mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.id").value(userId));
	}

	@Test
	void machineClientCannotBecomeCoordinatorEvenWithLocalRowAndUserAudience() throws Exception {
		String machine = fetchToken("coordinator-it", "grant_type=client_credentials&client_id=itsm-machine&client_secret=it-only-secret");
		var claims = JSON.readTree(java.util.Base64.getUrlDecoder().decode(machine.split("\\.")[1]));
		jdbc.update("""
				INSERT INTO users(identity_issuer, identity_subject, login, display_name)
				VALUES (?, ?, 'service-account-itsm-machine', 'Not a human')
				""", issuer("coordinator-it"), claims.get("sub").asText());
		assertDenied(machine);
		mvc.perform(post("/api/integrations/itsm/batches")
				.header("Authorization", "Bearer " + machine)
				.header("Idempotency-Key", java.util.UUID.randomUUID().toString())
				.header("X-Source-Id", "kan22-test").header("X-Source-Version", "1")
				.contentType("application/json").content("[]"))
				.andExpect(status().isAccepted());
	}

	@Test
	void missingOrInvalidTokenIs401WithoutLeakingTokenAndHealthStaysPublic() throws Exception {
		mvc.perform(get("/health")).andExpect(status().isOk());
		mvc.perform(get("/api/me")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("HUMAN_AUTH_REQUIRED"));
		String broken = token.substring(0, token.lastIndexOf('.') + 1) + "invalid-signature";
		var response = mvc.perform(get("/api/me").header("Authorization", "Bearer " + broken))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("Human access token is required"))
				.andReturn().getResponse().getContentAsString();
		assertThat(response).doesNotContain(broken);
	}

	private void assertDenied(String bearer) throws Exception {
		mvc.perform(get("/api/me").header("Authorization", "Bearer " + bearer))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("COORDINATOR_ACCESS_DENIED"))
				.andExpect(jsonPath("$.message").value("Coordinator access is not granted"));
	}

	static String issuer(String realm) {
		return "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080) + "/realms/" + realm;
	}

	static String userToken(String realm, String username) throws Exception {
		return fetchToken(realm, "grant_type=password&client_id=dashboard-web&username="
				+ URLEncoder.encode(username, StandardCharsets.UTF_8) + "&password=it-only-password");
	}

	static String fetchToken(String realm, String body) throws Exception {
		var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder()
				.uri(URI.create(issuer(realm) + "/protocol/openid-connect/token"))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as("Keycloak token endpoint").isEqualTo(200);
		return JSON.readTree(response.body()).get("access_token").asText();
	}

	static byte[] realm(String name) {
		var audience = Map.of("name", "user-audience", "protocol", "openid-connect", "protocolMapper", "oidc-audience-mapper",
				"config", Map.of("included.custom.audience", "dashboard-api", "access.token.claim", "true"));
		var machineAudience = Map.of("name", "machine-audience", "protocol", "openid-connect", "protocolMapper", "oidc-audience-mapper",
				"config", Map.of("included.custom.audience", "itsm-ingest", "access.token.claim", "true"));
		return JSON.writeValueAsBytes(Map.of(
				"realm", name, "enabled", true, "registrationAllowed", false, "sslRequired", "none",
				"roles", Map.of("realm", List.of(Map.of("name", "itsm:import"))),
				"clients", List.of(
						Map.of("clientId", "dashboard-web", "publicClient", true, "directAccessGrantsEnabled", true,
								"standardFlowEnabled", false, "protocolMappers", List.of(audience)),
						Map.of("clientId", "itsm-machine", "secret", "it-only-secret", "serviceAccountsEnabled", true,
								"standardFlowEnabled", false, "protocolMappers", List.of(audience, machineAudience))),
				"users", List.of(
						Map.of("id", SUBJECT, "username", "coordinator", "enabled", true,
								"firstName", "Test", "lastName", "Coordinator", "email", "test@example.invalid", "emailVerified", true,
								"credentials", List.of(Map.of("type", "password", "value", "it-only-password", "temporary", false))),
						Map.of("username", "service-account-itsm-machine", "enabled", true,
								"serviceAccountClientId", "itsm-machine", "realmRoles", List.of("itsm:import")))));
	}

	@RestController
	static class ProbeController {
		private final JdbcTemplate jdbc;
		ProbeController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
		@PostMapping("/api/coordinator-test/write")
		void write() { jdbc.update("UPDATE users SET display_name = 'write-1'"); }
	}
}
