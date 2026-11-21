package ru.hardcode.dashboard.controller;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import ru.hardcode.dashboard.ItsmPostgresTestcontainersConfiguration;
import ru.hardcode.dashboard.client.Kan16JwtTestConfiguration;
import ru.hardcode.dashboard.client.KeycloakAdminTestClient;
import ru.hardcode.dashboard.client.KeycloakPkceClient;
import ru.hardcode.dashboard.client.KeycloakTestContainer;
import ru.hardcode.dashboard.client.TestJwtSigner;
import ru.hardcode.dashboard.service.ItsmSnapshotPublicationService;
import ru.hardcode.dashboard.worker.ItsmWorkerSqlRepository;
import ru.hardcode.dashboard.worker.ItsmWorkerTransactionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KAN-16: human login with a real Keycloak, the stateless Bearer chain for {@code /api/me} and
 * its separation from the public {@code /health} and the KAN-27 machine routes with real tokens.
 *
 * <p>Both decoders use the real container's issuer and public keys. The human JWKS also
 * includes a test key, so the negative claim cases can pass signature verification first.
 */
@SpringBootTest(properties = {
		"itsm.worker.enabled=false",
		"request-logging.max-cached-body-size=1KB"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@Import({
		ItsmPostgresTestcontainersConfiguration.class,
		KeycloakTestContainer.class,
		Kan16JwtTestConfiguration.class
})
class HumanApiSecurityIT {

	private static final String ME_PATH = "/api/me";
	private static final String BATCH_PATH = "/api/integrations/itsm/batches";
	private static final String DASHBOARD_PATH = "/api/dashboard/incidents";
	private static final String HUMAN_AUDIENCE = KeycloakTestContainer.HUMAN_AUDIENCE;
	private static final String MACHINE_AUDIENCE = KeycloakTestContainer.MACHINE_AUDIENCE;
	/** The project setting of application.yaml (PT30S); the boundary tests use it explicitly. */
	private static final Duration SKEW = Duration.ofSeconds(30);

	private static final byte[] SNAPSHOT = "[]".getBytes(StandardCharsets.UTF_8);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private Kan16JwtTestConfiguration.Kan16SignedTokens signedTokens;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private ItsmSnapshotPublicationService publication;

	private KeycloakPkceClient keycloak;

	@DynamicPropertySource
	static void authProperties(DynamicPropertyRegistry registry) {
		registry.add("app.auth.human.issuer", KeycloakTestContainer::issuer);
		registry.add("app.auth.human.jwk-set-uri", Kan16JwtTestConfiguration::combinedJwkSetUri);
		registry.add("app.auth.human.audience", () -> HUMAN_AUDIENCE);
		registry.add("itsm.integration.auth.issuer", KeycloakTestContainer::issuer);
		registry.add("itsm.integration.auth.jwk-set-uri", KeycloakTestContainer::jwkSetUri);
		registry.add("itsm.integration.auth.audience", () -> MACHINE_AUDIENCE);
		registry.add("itsm.integration.auth.client-id", () -> KeycloakTestContainer.MACHINE_CLIENT_ID);
		registry.add("itsm.integration.auth.import-role", () -> KeycloakTestContainer.IMPORT_ROLE);
	}

	@BeforeAll
	static void prepareFixtureAccounts() throws Exception {
		KeycloakAdminTestClient admin = admin();
		if (!admin.userExists(KeycloakTestContainer.ADMIN_CREATED_USERNAME)) {
			admin.createUser(KeycloakTestContainer.ADMIN_CREATED_USERNAME,
					KeycloakTestContainer.ADMIN_CREATED_EMAIL,
					KeycloakTestContainer.ADMIN_CREATED_PASSWORD,
					KeycloakTestContainer.DASHBOARD_USERS_GROUP);
		}
	}

	@BeforeEach
	void resetDatabase() throws Exception {
		jdbcTemplate.execute("""
				TRUNCATE itsm_ingest_batch, itsm_snapshot, itsm_snapshot_source, itsm_incident CASCADE
				""");
		keycloak = new KeycloakPkceClient(KeycloakTestContainer.issuer());
		var subject = SignedJWT.parse(keycloak.login(KeycloakTestContainer.TEST_USERNAME, KeycloakTestContainer.TEST_PASSWORD)).getJWTClaimsSet().getSubject();
		for (String sub : java.util.List.of(subject, "kan16-skew-subject"))
			jdbcTemplate.update("INSERT INTO users(identity_issuer,identity_subject,login,display_name) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",KeycloakTestContainer.issuer(),sub,"test","Test");
	}

	// --- positive -------------------------------------------------------------------------

	@Test
	void preCreatedUserLogsInWithAuthorizationCodePkce_andMeReturnsSub() throws Exception {
		String token = keycloak.login(KeycloakTestContainer.TEST_USERNAME, KeycloakTestContainer.TEST_PASSWORD);
		SignedJWT jwt = SignedJWT.parse(token);

		assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo(KeycloakTestContainer.issuer());
		assertThat(token).isNotBlank();

		mockMvc.perform(get(ME_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.subject").value(jwt.getJWTClaimsSet().getSubject()))
				.andExpect(jsonPath("$.username").value(KeycloakTestContainer.TEST_USERNAME))
				.andExpect(jsonPath("$.email").value(KeycloakTestContainer.TEST_EMAIL));
	}

	@Test
	void tokenWithSeveralAudiencesIncludingRequired_isAccepted() throws Exception {
		String token = keycloak.login(KeycloakTestContainer.TEST_USERNAME, KeycloakTestContainer.TEST_PASSWORD);
		String jwt = SignedJWT.parse(token).getJWTClaimsSet().getSubject();

		// The public client's tokens carry the required audience next to others (e.g. account);
		// a locally signed token with several audiences must pass the same validation.
		String foreignSigned = signedTokens.signer().token(TestJwtSigner.claims()
				.issuer(KeycloakTestContainer.issuer())
				.subject(jwt).claim("azp","dashboard-web")
				.audience("account", "other-api", HUMAN_AUDIENCE)
				.expiresAt(Instant.now().plus(Duration.ofMinutes(5))));

		mockMvc.perform(get(ME_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + foreignSigned))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.subject").value(jwt));

		// The same token speaks for the human route only: no machine route accepts it.
		mockMvc.perform(batchRequest(foreignSigned))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("ITSM_MACHINE_AUTH_REQUIRED"));
		assertThat(batchCount()).isZero();
	}

	@Test
	void protectedRouteWithPublicHealth_staysClosedAndHealthStaysOpen() throws Exception {
		String token = keycloak.login(KeycloakTestContainer.TEST_USERNAME, KeycloakTestContainer.TEST_PASSWORD);

		mockMvc.perform(get("/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));

		mockMvc.perform(get(ME_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk());
	}

	// --- negative -------------------------------------------------------------------------

	@Test
	void withoutToken_returns401InApiErrorFormatWithoutLeakingToken() throws Exception {
		mockMvc.perform(get(ME_PATH))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"dashboard-human\""))
				.andExpect(jsonPath("$.code").value("HUMAN_AUTH_REQUIRED"))
				.andExpect(jsonPath("$.message").value("Human access token is required"))
				.andExpect(jsonPath("$.accessToken").doesNotExist())
				.andExpect(jsonPath("$.token").doesNotExist());
	}

	@Test
	void basicCredentials_doNotAuthenticateTheApi() throws Exception {
		mockMvc.perform(get(ME_PATH).header(HttpHeaders.AUTHORIZATION,
						"Basic " + Base64.getEncoder().encodeToString("user:user".getBytes(StandardCharsets.UTF_8))))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("HUMAN_AUTH_REQUIRED"));
	}

	@Test
	void tokenWithForeignSignature_returns401() throws Exception {
		String foreign = TestJwtSigner.foreignToken(KeycloakTestContainer.issuer(), HUMAN_AUDIENCE,
				Duration.ofMinutes(5));

		assertUnauthorizedWithoutToken(foreign);
	}

	@Test
	void tokenWithWrongIssuer_returns401() throws Exception {
		String token = signedTokens.signer().token(TestJwtSigner.claims()
				.issuer(KeycloakTestContainer.issuer().replace("/dashboard", "/other-realm"))
				.audience(HUMAN_AUDIENCE)
				.expiresAt(Instant.now().plus(Duration.ofMinutes(5))));

		assertUnauthorizedWithoutToken(token);
	}

	@Test
	void tokenWithWrongAudience_returns401() throws Exception {
		String token = signedTokens.signer().token(TestJwtSigner.claims()
				.issuer(KeycloakTestContainer.issuer())
				.audience(MACHINE_AUDIENCE)
				.expiresAt(Instant.now().plus(Duration.ofMinutes(5))));

		assertUnauthorizedWithoutToken(token);
	}

	@Test
	void tokenWithoutAudience_returns401() throws Exception {
		String token = signedTokens.signer().token(TestJwtSigner.claims()
				.issuer(KeycloakTestContainer.issuer())
				.expiresAt(Instant.now().plus(Duration.ofMinutes(5))));

		assertUnauthorizedWithoutToken(token);
	}

	@Test
	void tokenWithoutExpiry_returns401() throws Exception {
		String token = signedTokens.signer().token(TestJwtSigner.claims()
				.issuer(KeycloakTestContainer.issuer())
				.audience(HUMAN_AUDIENCE));

		assertUnauthorizedWithoutToken(token);
	}

	@Test
	void expiredBeyondAllowedSkew_returns401() throws Exception {
		String token = signedTokens.signer().token(TestJwtSigner.claims()
				.issuer(KeycloakTestContainer.issuer())
				.audience(HUMAN_AUDIENCE)
				.issuedAt(Instant.now().minus(Duration.ofMinutes(10)))
				.expiresAt(Instant.now().minus(SKEW).minusSeconds(120)));

		assertUnauthorizedWithoutToken(token);
	}

	@Test
	void malformedBearer_returns401() throws Exception {
		mockMvc.perform(get(ME_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("HUMAN_AUTH_REQUIRED"))
				.andExpect(jsonPath("$.message").value("Human access token is required"));
	}

	// --- boundary of the clock skew ---------------------------------------------------------

	@Test
	void expiredWithinAllowedSkew_isAccepted_andJustBeforeTheBoundaryToo() throws Exception {
		String token = signedTokens.signer().token(TestJwtSigner.claims()
				.issuer(KeycloakTestContainer.issuer())
				.subject("kan16-skew-subject").claim("azp","dashboard-web")
				.audience(HUMAN_AUDIENCE)
				.expiresAt(Instant.now().minusSeconds(SKEW.toSeconds() - 10)));

		mockMvc.perform(get(ME_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.subject").value("kan16-skew-subject"));
	}

	@Test
	void expiredJustBeyondAllowedSkew_isRejected() throws Exception {
		String token = signedTokens.signer().token(TestJwtSigner.claims()
				.issuer(KeycloakTestContainer.issuer())
				.audience(HUMAN_AUDIENCE)
				.expiresAt(Instant.now().minusSeconds(SKEW.toSeconds() + 10)));

		assertUnauthorizedWithoutToken(token);
	}

	// --- separate audiences and routes ------------------------------------------------------

	@Test
	void machineTokenHasNoAccessToHumanRoute() throws Exception {
		String token = KeycloakPkceClient.accessToken(keycloak.clientCredentials(
				KeycloakTestContainer.MACHINE_CLIENT_ID, KeycloakTestContainer.ITSM_SENDER_SECRET));

		// The machine audience as Keycloak actually issues it (asserted below for the human token).
		assertThat(SignedJWT.parse(token).getJWTClaimsSet().getClaim("azp"))
				.isEqualTo(KeycloakTestContainer.MACHINE_CLIENT_ID);

		assertUnauthorizedWithoutToken(token);
	}

	@Test
	void machineTokenImportsAndOnlyTheHumanTokenReadsThePublishedDashboard() throws Exception {
		String machineToken = KeycloakPkceClient.accessToken(keycloak.clientCredentials(
				KeycloakTestContainer.MACHINE_CLIENT_ID, KeycloakTestContainer.ITSM_SENDER_SECRET));
		UUID batchId = UUID.randomUUID();
		String incidentId = "INC000016";
		String incidentPath = DASHBOARD_PATH + "/" + incidentId;

		mockMvc.perform(batchRequest(machineToken, batchId).content("""
				[{"id":"INC000016","stateEnum":"Assigned","priorityId":4,"submitDate":"2026-11-21T09:00:00Z",
				  "productName":"Keycloak IT", "solution":null}]
				"""))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.batchId").value(batchId.toString()));

		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM itsm_ingest_batch WHERE id = ?", Integer.class, batchId)).isEqualTo(1);
		var worker = new ItsmWorkerTransactionService(new ItsmWorkerSqlRepository(jdbcTemplate), transactionManager);
		assertThat(worker.claimAndProcess(publication, "kan16-test").batchId()).isEqualTo(batchId);

		String humanToken = keycloak.login(KeycloakTestContainer.TEST_USERNAME, KeycloakTestContainer.TEST_PASSWORD);
		mockMvc.perform(get(DASHBOARD_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + humanToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].id").value(incidentId));
		mockMvc.perform(get(incidentPath).header(HttpHeaders.AUTHORIZATION, "Bearer " + humanToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(incidentId));
		for (String path : new String[]{DASHBOARD_PATH, incidentPath}) {
			mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + machineToken))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
					.andExpect(jsonPath("$.message").value("Authentication is required"));
		}
	}

	@Test
	void humanTokenHasNoAccessToMachineRoute_andWritesNothing() throws Exception {
		String token = keycloak.login(KeycloakTestContainer.TEST_USERNAME, KeycloakTestContainer.TEST_PASSWORD);

		// The independent bearer source of the resource-server chain rejects the token: 401, not
		// 403 for a missing role, and no batch is written.
		mockMvc.perform(batchRequest(token, UUID.randomUUID()))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
						"Bearer realm=\"itsm-integration\", error=\"invalid_token\""))
				.andExpect(jsonPath("$.code").value("ITSM_MACHINE_AUTH_REQUIRED"))
				.andExpect(jsonPath("$.message").value("Machine bearer token is required"));

		assertThat(batchCount()).isZero();
	}

	@Test
	void machineRouteKeepsItsKan27Challenge() throws Exception {
		// Posted without a token (not merely without the header) so the challenge is exactly
		// KAN-27's: no error parameter for missing credentials.
		mockMvc.perform(post(BATCH_PATH)
						.contentType(MediaType.APPLICATION_JSON)
						.header(ItsmBatchController.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID())
						.header(ItsmBatchController.SOURCE_ID_HEADER, "kan16-source")
						.header(ItsmBatchController.SOURCE_VERSION_HEADER, 1)
						.content(SNAPSHOT))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"itsm-integration\""))
				.andExpect(jsonPath("$.code").value("ITSM_MACHINE_AUTH_REQUIRED"));

		assertThat(batchCount()).isZero();
	}

	// --- login and registration -------------------------------------------------------------

	@Test
	void preCreatedAccountWorksWithoutRegistration() {
		assertThat(new KeycloakPkceClient(KeycloakTestContainer.issuer())
				.login(KeycloakTestContainer.ADMIN_CREATED_USERNAME, KeycloakTestContainer.ADMIN_CREATED_PASSWORD))
				.isNotBlank();
	}

	@Test
	void unknownUserCannotLogIn() throws Exception {
		KeycloakPkceClient.Authorization authorization = keycloak.authorize();
		String unknown = "kan16-unknown-" + UUID.randomUUID();

		HttpResponse<String> response =
				keycloak.submitLogin(authorization.loginAction(), unknown, "kan16-unknown-password");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Location")).isEmpty();
		assertThat(response.body()).contains("Invalid username or password.").doesNotContain("access_token");
	}

	@Test
	void unknownUserCannotRegister() throws Exception {
		HttpResponse<String> page = keycloak.registrationPage();
		assertThat(page.statusCode()).isEqualTo(400);
		assertThat(page.body()).containsIgnoringCase("registration not allowed").doesNotContain("access_token");
	}

	@Test
	void passwordGrantIsDisabledAndRegistrationStaysOff() throws Exception {
		HttpResponse<String> grant = keycloak.passwordGrant(
				KeycloakTestContainer.TEST_USERNAME, KeycloakTestContainer.TEST_PASSWORD);
		assertThat(grant.statusCode()).isEqualTo(400);
		assertThat(KeycloakPkceClient.errorBody(grant).get("error").asString()).isEqualTo("unauthorized_client");

		assertThat(keycloak.authorize().loginAction().toLowerCase()).doesNotContain("registration");
		assertThat(admin().userGroups(KeycloakTestContainer.TEST_USERNAME))
				.containsExactlyElementsOf(KeycloakTestContainer.DASHBOARD_USERS_GROUP);
	}

	@Test
	void pkceIsEnforced() throws Exception {
		KeycloakPkceClient.Authorization authorization = keycloak.authorize();
		HttpResponse<String> login = keycloak.submitLogin(authorization.loginAction(),
				KeycloakTestContainer.TEST_USERNAME, KeycloakTestContainer.TEST_PASSWORD);
		String code = queryCode(login.headers().firstValue("Location").orElseThrow());

		HttpResponse<String> token = keycloak.exchangeWithoutVerifier(code);

		assertThat(token.statusCode()).isEqualTo(400);
		assertThat(KeycloakPkceClient.errorBody(token).get("error").asString()).isEqualTo("invalid_grant");
	}

	// --- helpers -----------------------------------------------------------------------------

	/** 401 of the human chain: API error format, own challenge, no token echoed back. */
	private void assertUnauthorizedWithoutToken(String token) throws Exception {
		MvcResult result = mockMvc.perform(get(ME_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
						"Bearer realm=\"dashboard-human\", error=\"invalid_token\""))
				.andExpect(jsonPath("$.code").value("HUMAN_AUTH_REQUIRED"))
				.andExpect(jsonPath("$.message").value("Human access token is required"))
				.andReturn();

		String rendered = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
		assertThat(rendered)
				.as("error body must not leak the token")
				.doesNotContain(token)
				.doesNotContain("eyJ")
				.hasSizeLessThan(256);
	}

	private MockHttpServletRequestBuilder batchRequest(String token) {
		return batchRequest(token, UUID.randomUUID());
	}

	private MockHttpServletRequestBuilder batchRequest(String token, UUID idempotencyKey) {
		MockHttpServletRequestBuilder request = post(BATCH_PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.header(ItsmBatchController.IDEMPOTENCY_KEY_HEADER, idempotencyKey)
				.header(ItsmBatchController.SOURCE_ID_HEADER, "kan16-source")
				.header(ItsmBatchController.SOURCE_VERSION_HEADER, 1)
				.content(SNAPSHOT);
		if (token != null) {
			request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		}
		return request;
	}

	private int batchCount() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM itsm_ingest_batch", Integer.class);
	}

	private static String queryCode(String location) {
		for (String pair : URI.create(location).getRawQuery().split("&")) {
			if (pair.startsWith("code=")) {
				return URLDecoder.decode(pair.substring(5), StandardCharsets.UTF_8);
			}
		}
		throw new IllegalStateException("No authorization code in the redirect");
	}

	private static KeycloakAdminTestClient admin() {
		return new KeycloakAdminTestClient(KeycloakTestContainer.issuer(),
				KeycloakTestContainer.ADMIN_USERNAME, KeycloakTestContainer.ADMIN_PASSWORD);
	}

}
