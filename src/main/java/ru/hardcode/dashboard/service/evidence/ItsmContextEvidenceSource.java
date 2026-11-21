package ru.hardcode.dashboard.service.evidence;

import ru.hardcode.dashboard.configuration.evidence.ConditionalOnEvidenceMapping;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriUtils;
import ru.hardcode.dashboard.configuration.evidence.McpEvidenceDemoProperties;
import ru.hardcode.dashboard.dto.McpEvidenceSource;

/**
 * Read-only ITSM incident context from the controlled stand ITSM source through its existing
 * incident contract, {@code GET /api/mock/incidents/{incidentId}}. That fixed record names the
 * incident and its assigned group. The evidence uses the assigned group from the stand response.
 *
 * <p>The assignment timestamp comes from the source record. The service checks it against
 * the requested evidence window.
 */
@Component
@ConditionalOnEvidenceMapping
public class ItsmContextEvidenceSource implements McpEvidenceSourcePort {

	static final String INCIDENT_PATH = "/api/mock/incidents/{incidentId}";

	/** Stand priorities, normalized to the ITSM stand's own scale of urgency. */
	private static final Map<String, String> PRIORITIES = Map.of(
			"Низкий", "LOW",
			"Средний", "MEDIUM",
			"Высокий", "HIGH",
			"Наивысший", "CRITICAL"
	);

	private final RestClient restClient;
	private final McpEvidenceDemoProperties properties;

	public ItsmContextEvidenceSource(
			@Qualifier("evidenceRestClient") RestClient restClient,
			McpEvidenceDemoProperties properties
	) {
		this.restClient = restClient;
		this.properties = properties;
	}

	@Override
	public McpEvidenceSource source() {
		return McpEvidenceSource.ITSM;
	}

	@Override
	public SourceReading read(EvidenceQuery query) {
		McpEvidenceDemoProperties.ItsmSource source = properties.itsm();
		if (source == null || source.baseUrl() == null || source.baseUrl().isBlank()) {
			throw McpEvidenceSourceException.unavailable(source(), query.serviceKey(), null);
		}
		ItsmIncident incident;
		try {
			incident = restClient.get()
					.uri(URI.create(incidentUri(source.baseUrl(), query.incidentId())))
					.accept(MediaType.APPLICATION_JSON)
					.retrieve()
					.body(new ParameterizedTypeReference<>() {
					});
		} catch (RestClientResponseException exception) {
			// The fixed record is absent or hidden from this read-only client.
			if (exception.getStatusCode() == HttpStatus.NOT_FOUND) {
				throw McpEvidenceSourceException.unavailable(source(), query.serviceKey(), exception);
			}
			throw McpEvidenceSourceException.rejected(source(), query.serviceKey());
		} catch (RestClientException exception) {
			throw McpEvidenceSourceException.unavailable(source(), query.serviceKey(), exception);
		}

		if (incident == null || !query.incidentId().equals(incident.id())) {
			// The source must confirm which incident the record belongs to.
			throw McpEvidenceSourceException.notAttributed(
					source(), query.serviceKey(), query.incidentId(), query.scenarioRunId());
		}
		Instant observedAt = parseAssignmentTime(incident.lastAssignmentAt());
		if (observedAt == null) {
			throw McpEvidenceSourceException.rejected(source(), query.serviceKey());
		}
		String command = assignedCommand(incident);
		return new SourceReading(
				"itsm:" + incident.id(),
				observedAt,
				incident.id(),
				query.scenarioRunId(),
				new Observation(
						null,
						null,
						safeLevel(incident.priority()),
						command,
						null,
						null,
						safeState(incident.stateEnum())
				)
		);
	}

	private static Instant parseAssignmentTime(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Instant.parse(value);
		} catch (DateTimeParseException exception) {
			return null;
		}
	}

	/**
	 * Report the current assigned team as recorded by the ITSM stand.
	 */
	static String assignedCommand(ItsmIncident incident) {
		return incident.assignedGroupName() == null ? null : incident.assignedGroupName().strip();
	}

	/** The bounded priority scale of the stand, or {@code null} for anything else. */
	static String safeLevel(String priority) {
		if (priority == null) {
			return null;
		}
		return PRIORITIES.get(priority.trim());
	}

	static String safeState(String state) {
		if (state == null || state.isBlank()) {
			return null;
		}
		StringBuilder safe = new StringBuilder(32);
		for (char character : state.toCharArray()) {
			if (safe.length() == 32) {
				break;
			}
			if (Character.isLetterOrDigit(character) || character == '_' || character == '-') {
				safe.append(character);
			}
		}
		return safe.isEmpty() ? null : safe.toString();
	}

	private static String normalizedBaseUrl(String baseUrl) {
		String trimmed = baseUrl.trim();
		return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
	}

	static String incidentUri(String baseUrl, String incidentId) {
		return normalizedBaseUrl(baseUrl) + "/api/mock/incidents/" + UriUtils.encodePathSegment(incidentId, StandardCharsets.UTF_8);
	}

	/**
	 * The stand incident contract shared with {@code itsm-mock}. Extra fields are ignored; the
	 * fields the evidence relies on are bound explicitly.
	 */
	record ItsmIncident(
			String id,
			String stateEnum,
			String priority,
			String assignedGroupName,
			String lastAssignmentAt
	) {
	}
}
