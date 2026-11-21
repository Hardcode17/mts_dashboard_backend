package ru.hardcode.dashboard.contract;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import ru.hardcode.dashboard.ItsmPostgresTestcontainersConfiguration;
import ru.hardcode.dashboard.client.ItsmMachineJwtTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
		"itsm.integration.auth.issuer=" + ItsmMachineJwtTestConfiguration.ISSUER,
		"itsm.integration.auth.jwk-set-uri=https://keycloak.test/realms/itsm/protocol/openid-connect/certs",
		"itsm.integration.auth.audience=" + ItsmMachineJwtTestConfiguration.AUDIENCE,
		"itsm.integration.auth.client-id=" + ItsmMachineJwtTestConfiguration.CLIENT_ID,
		"itsm.integration.auth.import-role=" + ItsmMachineJwtTestConfiguration.IMPORT_ROLE,
		"spring.security.user.name=operator",
		"spring.security.user.password=kan20-test-password"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@Import({ItsmPostgresTestcontainersConfiguration.class, ItsmMachineJwtTestConfiguration.class})
class OpenApiContractIT {

	@Autowired private MockMvc mvc;
	@Autowired private JwtEncoder encoder;

	@Test
	void servesTheCanonicalDocumentsAndValidatesPositiveAndNegativeExamples() throws Exception {
		Path docs = Path.of("docs");
		for (String filename : List.of("itsm-ingest.openapi.yaml", "dashboard-api.openapi.yaml")) {
			mvc.perform(get("/openapi/" + filename).with(httpBasic("operator", "kan20-test-password")))
					.andExpect(status().isOk())
					.andExpect(content().bytes(Files.readAllBytes(docs.resolve(filename))));
		}
		assertThat(OpenApiContractValidator.check(docs, null)).isEmpty();
	}

	@Test
	void deployedBatchRoutesReturnDocumentedErrorBodies() throws Exception {
		String batch = "/api/integrations/itsm/batches/3f2c1b9e-6a43-4c1a-9a37-0e5d7b2d4c11";
		mvc.perform(get(batch).header("Authorization", "Bearer invalid-token"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("ITSM_MACHINE_AUTH_REQUIRED"))
				.andExpect(jsonPath("$.message").isNotEmpty());
		mvc.perform(get(batch).header("Authorization",
						"Bearer " + ItsmMachineJwtTestConfiguration.machineToken(encoder)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ITSM_BATCH_NOT_FOUND"))
				.andExpect(jsonPath("$.message").isNotEmpty());
	}
}
