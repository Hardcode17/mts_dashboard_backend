package ru.hardcode.dashboard.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ru.hardcode.dashboard.client.ItsmMachineJwtTestConfiguration;
import ru.hardcode.dashboard.configuration.ItsmIntegrationSecurityConfiguration;
import ru.hardcode.dashboard.dto.ItsmIncidentRecord;
import ru.hardcode.dashboard.dto.ItsmIncidentSnapshot;
import ru.hardcode.dashboard.service.ItsmIncidentSnapshotParser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
		controllers = ItsmIncidentSnapshotParserIT.DecoderAdapter.class,
		properties = {
				"itsm.integration.auth.issuer=" + ItsmMachineJwtTestConfiguration.ISSUER,
				"itsm.integration.auth.jwk-set-uri=https://keycloak.test/realms/itsm/protocol/openid-connect/certs",
				"itsm.integration.auth.audience=" + ItsmMachineJwtTestConfiguration.AUDIENCE,
				"itsm.integration.auth.client-id=" + ItsmMachineJwtTestConfiguration.CLIENT_ID,
				"itsm.integration.auth.import-role=" + ItsmMachineJwtTestConfiguration.IMPORT_ROLE
		}
)
@Import({
		ItsmIncidentSnapshotParserIT.DecoderAdapter.class,
		ItsmIncidentSnapshotParser.class,
		ItsmIntegrationSecurityConfiguration.class,
		ItsmMachineJwtTestConfiguration.class
})
class ItsmIncidentSnapshotParserIT {

	private static final String ADAPTER_PATH = "/api/integrations/itsm/test-decode";
	private static final String SAMPLE = "itsm/character_problems.json";
	private static final String MISSING = "must be a single JSON array";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtEncoder jwtEncoder;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void decodesEveryOriginalIncidentThroughAuthenticatedHttp() throws Exception {
		byte[] payload = fixture();
		String response = mockMvc.perform(decode(payload))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.records", hasSize(44)))
				.andExpect(jsonPath("$.records[0].id").value("INC000021931151"))
				.andExpect(jsonPath("$.records[0].state").value("Assigned"))
				.andExpect(jsonPath("$.records[0].priorityId").value(1))
				.andExpect(jsonPath("$.records[0].submitDate").value("2026-11-08T12:02:29Z"))
				.andExpect(jsonPath("$.records[0].deadline").value("2026-11-22T12:02:29Z"))
				.andExpect(jsonPath("$.records[0].technicalReferenceDate").value(nullValue()))
				.andExpect(jsonPath("$.records[0].solution").value(nullValue()))
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

		JsonNode original = objectMapper.readTree(payload);
		JsonNode records = objectMapper.readTree(response).get("records");
		List<String> ids = records.values().stream().map(record -> record.get("id").asString()).toList();
		assertThat(ids).hasSize(44).doesNotHaveDuplicates()
				.containsExactlyElementsOf(original.values().stream().map(record -> record.get("id").asString()).toList());
		assertThat(records.values()).filteredOn(record -> record.get("deadline").isNull()).hasSize(3);
		assertThat(records.values()).filteredOn(record -> "Research".equals(record.get("state").asString()))
				.hasSize(5).allSatisfy(record -> assertThat(record.get("completionDate").asString()).endsWith("Z"));
		assertThat(records.values()).allSatisfy(record -> {
			assertThat(record.get("submitDate").asString()).endsWith("Z");
			assertThat(record.get("priorityId").isIntegralNumber()).isTrue();
			assertThat(record.get("technicalReferenceDate").isNull()).isTrue();
		});
	}

	@Test
	void legacyPrioritySentinelAndFractionalDeadlineUseNormalizedValues() throws Exception {
		mockMvc.perform(decode("""
				[{"id":"INC-1","stateEnum":"Research","priorityId":4,"priority":"Наивысший",
				  "submitDate":"2026-11-08T12:02:29Z","deadline":"2026-11-21T12:34:56.123456+00:00",
				  "completionDate":"2026-11-08T12:05:32.123Z","waitingExitDate":"0001-01-01T00:00:00",
				  "solution":"Проанализировано"}]
				""".getBytes(StandardCharsets.UTF_8)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.records[0].deadline").value("2026-11-21T12:34:56.123456Z"))
				.andExpect(jsonPath("$.records[0].completionDate").value("2026-11-08T12:05:32.123Z"))
				.andExpect(jsonPath("$.records[0].technicalReferenceDate").value(nullValue()));
	}

	@Test
	void truncatedPayloadIsRejectedWithoutReturningRecords() throws Exception {
		byte[] payload = fixture();
		mockMvc.perform(decode(Arrays.copyOf(payload, payload.length / 2)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("ITSM_INVALID_PAYLOAD"))
				.andExpect(jsonPath("$.message").value(containsString(MISSING)))
				.andExpect(jsonPath("$.records").doesNotExist());
	}

	@Test
	void duplicatedIdRejectsTheEntireSnapshot() throws Exception {
		ArrayNode payload = (ArrayNode) objectMapper.readTree(fixture());
		payload.add(payload.get(0));
		mockMvc.perform(decode(objectMapper.writeValueAsBytes(payload)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("ITSM_DUPLICATE_RECORD"))
				.andExpect(jsonPath("$.message").value(containsString("index 44")))
				.andExpect(jsonPath("$.message").value(not(containsString("INC000021931151"))))
				.andExpect(jsonPath("$.records").doesNotExist());
	}

	@ParameterizedTest
	@ValueSource(strings = {"stateEnum", "priorityId"})
	void invalidFieldReturnsSafeDiagnosticForEntireSnapshot(String field) throws Exception {
		ArrayNode payload = (ArrayNode) objectMapper.readTree(fixture());
		((tools.jackson.databind.node.ObjectNode) payload.get(43)).put(field, "not-a-valid-value");
		mockMvc.perform(decode(objectMapper.writeValueAsBytes(payload)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("ITSM_INVALID_RECORD"))
				.andExpect(jsonPath("$.message").value(containsString("index 43")))
				.andExpect(jsonPath("$.message").value(containsString("field " + field)))
				.andExpect(jsonPath("$.message").value(not(containsString("not-a-valid-value"))))
				.andExpect(jsonPath("$.records").doesNotExist());
	}

	@Test
	void adapterUsesTheRealMachineSecurityFilter() throws Exception {
		mockMvc.perform(post(ADAPTER_PATH).contentType(MediaType.APPLICATION_JSON).content("[]"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("ITSM_MACHINE_AUTH_REQUIRED"));
	}

	private MockHttpServletRequestBuilder decode(byte[] content) {
		return post(ADAPTER_PATH)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ItsmMachineJwtTestConfiguration.machineToken(jwtEncoder))
				.contentType(MediaType.APPLICATION_JSON)
				.content(content);
	}

	private static byte[] fixture() throws IOException {
		return new ClassPathResource(SAMPLE).getContentAsByteArray();
	}

	/** Registered only in this test. The production intake still just accepts bytes for processing. */
	@RestController
	static class DecoderAdapter {

		private final ItsmIncidentSnapshotParser parser;

		DecoderAdapter(ItsmIncidentSnapshotParser parser) {
			this.parser = parser;
		}

		@PostMapping(path = ADAPTER_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
		ItResponse decode(@RequestBody byte[] payload) {
			ItsmIncidentSnapshot snapshot = parser.parse(payload);
			return new ItResponse(snapshot.records().stream().map(ItRecord::from).toList());
		}
	}

	private record ItResponse(List<ItRecord> records) {
	}

	private record ItRecord(
			String id,
			String state,
			int priorityId,
			String submitDate,
			String deadline,
			String completionDate,
			String technicalReferenceDate,
			String solution
	) {
		private static ItRecord from(ItsmIncidentRecord record) {
			return new ItRecord(
					record.id(), record.state().name(), record.priorityId(), record.submitDate().toString(),
					java.util.Objects.toString(record.deadline(), null),
					java.util.Objects.toString(record.completionDate(), null),
					java.util.Objects.toString(record.technicalReferenceDate(), null),
					record.solution()
			);
		}
	}
}
