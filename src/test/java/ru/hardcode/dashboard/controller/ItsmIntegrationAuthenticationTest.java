package ru.hardcode.dashboard.controller;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.hardcode.dashboard.client.ItsmMachineJwtTestConfiguration;
import ru.hardcode.dashboard.configuration.ItsmIntegrationSecurityConfiguration;
import ru.hardcode.dashboard.service.ItsmBatchIntakeService;
import ru.hardcode.dashboard.service.ItsmBatchQueryService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
		controllers = ItsmBatchController.class,
		properties = {
				"request-logging.max-cached-body-size=64B",
				"itsm.integration.auth.issuer=" + ItsmMachineJwtTestConfiguration.ISSUER,
				"itsm.integration.auth.jwk-set-uri=https://keycloak.test/realms/itsm/protocol/openid-connect/certs",
				"itsm.integration.auth.audience=" + ItsmMachineJwtTestConfiguration.AUDIENCE,
				"itsm.integration.auth.client-id=" + ItsmMachineJwtTestConfiguration.CLIENT_ID,
				"itsm.integration.auth.import-role=" + ItsmMachineJwtTestConfiguration.IMPORT_ROLE
		}
)
@Import({ItsmIntegrationSecurityConfiguration.class, ItsmMachineJwtTestConfiguration.class})
class ItsmIntegrationAuthenticationTest {

	private static final String BATCH_PATH = "/api/integrations/itsm/batches";
	private static final String SOURCE_ID = "itsm-source";
	private static final long SOURCE_VERSION = 1;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtEncoder jwtEncoder;

	@MockitoBean
	private ItsmBatchIntakeService intakeService;

	@MockitoBean
	private ItsmBatchQueryService queryService;

	@Test
	void postWithoutToken_returns401() throws Exception {
		mockMvc.perform(postBatch(UUID.randomUUID(), "[]"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"itsm-integration\""))
				.andExpect(jsonPath("$.code").value("ITSM_MACHINE_AUTH_REQUIRED"))
				.andExpect(jsonPath("$.message").value("Machine bearer token is required"));

		verifyNoInteractions(intakeService);
	}

	@Test
	void humanTokenWithImportRoleButWrongAzp_returns403() throws Exception {
		String token = ItsmMachineJwtTestConfiguration.token(
				jwtEncoder,
				ItsmMachineJwtTestConfiguration.ISSUER,
				ItsmMachineJwtTestConfiguration.AUDIENCE,
				"dashboard-web",
				List.of(ItsmMachineJwtTestConfiguration.IMPORT_ROLE),
				null
		);

		mockMvc.perform(postBatch(UUID.randomUUID(), "[]").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ITSM_MACHINE_ACCESS_DENIED"))
				.andExpect(jsonPath("$.message").value("Machine client identity or itsm import permission is missing"));

		verifyNoInteractions(intakeService);
	}

	@Test
	void machineTokenWithoutImportRole_returns403() throws Exception {
		String token = ItsmMachineJwtTestConfiguration.token(
				jwtEncoder,
				ItsmMachineJwtTestConfiguration.ISSUER,
				ItsmMachineJwtTestConfiguration.AUDIENCE,
				ItsmMachineJwtTestConfiguration.CLIENT_ID,
				List.of("dashboard-user"),
				null
		);

		mockMvc.perform(postBatch(UUID.randomUUID(), "[]").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ITSM_MACHINE_ACCESS_DENIED"));

		verifyNoInteractions(intakeService);
	}

	@Test
	void wrongAudience_returns401() throws Exception {
		String token = ItsmMachineJwtTestConfiguration.token(
				jwtEncoder,
				ItsmMachineJwtTestConfiguration.ISSUER,
				"another-api",
				ItsmMachineJwtTestConfiguration.CLIENT_ID,
				List.of(ItsmMachineJwtTestConfiguration.IMPORT_ROLE),
				null
		);

		mockMvc.perform(postBatch(UUID.randomUUID(), "[]").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("ITSM_MACHINE_AUTH_REQUIRED"));

		verifyNoInteractions(intakeService);
	}

	@Test
	void wrongIssuer_returns401() throws Exception {
		String token = ItsmMachineJwtTestConfiguration.token(
				jwtEncoder,
				"https://keycloak.test/realms/other",
				ItsmMachineJwtTestConfiguration.AUDIENCE,
				ItsmMachineJwtTestConfiguration.CLIENT_ID,
				List.of(ItsmMachineJwtTestConfiguration.IMPORT_ROLE),
				null
		);

		mockMvc.perform(postBatch(UUID.randomUUID(), "[]").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("ITSM_MACHINE_AUTH_REQUIRED"));

		verifyNoInteractions(intakeService);
	}

	@Test
	void machineTokenWithKeycloakClientRole_returns202WithoutCsrf() throws Exception {
		UUID batchId = UUID.randomUUID();
		given(intakeService.receive(anyString(), anyString(), anyString(), any(byte[].class)))
				.willReturn(batchId);
		String token = ItsmMachineJwtTestConfiguration.token(
				jwtEncoder,
				ItsmMachineJwtTestConfiguration.ISSUER,
				ItsmMachineJwtTestConfiguration.AUDIENCE,
				ItsmMachineJwtTestConfiguration.CLIENT_ID,
				null,
				List.of(ItsmMachineJwtTestConfiguration.IMPORT_ROLE)
		);

		mockMvc.perform(postBatch(batchId, "[]").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isAccepted())
				.andExpect(header().string(HttpHeaders.LOCATION, BATCH_PATH + "/" + batchId))
				.andExpect(jsonPath("$.batchId").value(batchId.toString()));
	}

	@Test
	void wrongContentType_returnsConsistent415() throws Exception {
		String token = ItsmMachineJwtTestConfiguration.machineToken(jwtEncoder);

		mockMvc.perform(post(BATCH_PATH)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.header(ItsmBatchController.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID())
						.header(ItsmBatchController.SOURCE_ID_HEADER, SOURCE_ID)
						.header(ItsmBatchController.SOURCE_VERSION_HEADER, SOURCE_VERSION)
						.contentType(MediaType.TEXT_PLAIN)
						.content("[]"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

		verifyNoInteractions(intakeService);
	}

	@Test
	void oversizedJsonSnapshot_returnsStandard413BeforeService() throws Exception {
		String token = ItsmMachineJwtTestConfiguration.machineToken(jwtEncoder);
		String oversizedBody = "[{\"id\":\"" + "a".repeat(80) + "\"}]";

		mockMvc.perform(postBatch(UUID.randomUUID(), oversizedBody)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isContentTooLarge())
				.andExpect(header().exists("X-Request-Id"))
				.andExpect(jsonPath("$.code").value("REQUEST_BODY_TOO_LARGE"))
				.andExpect(jsonPath("$.message").value("Request body exceeds 64 bytes"));

		verifyNoInteractions(intakeService);
	}

	private static MockHttpServletRequestBuilder postBatch(UUID idempotencyKey, String body) {
		return post(BATCH_PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.header(ItsmBatchController.IDEMPOTENCY_KEY_HEADER, idempotencyKey)
				.header(ItsmBatchController.SOURCE_ID_HEADER, SOURCE_ID)
				.header(ItsmBatchController.SOURCE_VERSION_HEADER, SOURCE_VERSION)
				.content(body);
	}
}
