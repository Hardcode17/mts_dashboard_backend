package ru.hardcode.dashboard.service.evidence;

import ru.hardcode.dashboard.configuration.evidence.ConditionalOnEvidenceMapping;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.hardcode.dashboard.configuration.evidence.McpEvidenceDemoProperties;
import ru.hardcode.dashboard.dto.McpEvidenceIncidentContextResponse;
import ru.hardcode.dashboard.dto.McpEvidenceObservation;
import ru.hardcode.dashboard.dto.McpEvidenceRef;
import ru.hardcode.dashboard.dto.McpEvidenceServiceSourceRequest;
import ru.hardcode.dashboard.dto.McpEvidenceSource;
import ru.hardcode.dashboard.dto.McpEvidenceSourceResponse;
import ru.hardcode.dashboard.dto.McpEvidenceUnavailableSource;
import ru.hardcode.dashboard.exception.AppError;
import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.AppException;
import ru.hardcode.dashboard.service.evidence.McpEvidenceSourcePort.EvidenceQuery;
import ru.hardcode.dashboard.service.evidence.McpEvidenceSourcePort.SourceReading;

/**
 * Bounded read-only evidence for the single explicitly mapped demo scenario.
 *
 * <p>The mapping is fixed by configuration: one scenario id, one incident, one scenario run
 * and the service keys bound to it. Every call must name the same incident and scenario run.
 * The evidence window must be non-empty, ordered, expressed in whole microseconds (what the
 * stand sources and the diagnostics database store), bounded by
 * {@code mcp.evidence.demo.max-window} and contained in the observation window. A service key
 * outside the mapping is rejected before any source is contacted.
 */
@Service
@ConditionalOnEvidenceMapping
public class McpEvidenceService {

	static final int MAX_EVIDENCE_ID_LENGTH = 128;
	static final int MAX_LEVEL_LENGTH = 16;
	static final int MAX_COMMAND_LENGTH = 128;
	static final int MAX_STATE_LENGTH = 64;

	private static final Pattern EVIDENCE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
	private static final Pattern LEVEL = Pattern.compile("[A-Z]{1,16}");
	private static final Pattern COMMAND = Pattern.compile("[\\p{L}\\p{N} ._:/()-]{1,128}");
	private static final Pattern STATE = Pattern.compile("[A-Za-z0-9_-]{1,64}");

	private final McpEvidenceDemoProperties properties;
	private final List<McpEvidenceSourcePort> sourcePorts;
	private final Clock clock;

	@Autowired
	public McpEvidenceService(McpEvidenceDemoProperties properties, List<McpEvidenceSourcePort> sourcePorts) {
		this(properties, sourcePorts, Clock.systemUTC());
	}

	McpEvidenceService(McpEvidenceDemoProperties properties, List<McpEvidenceSourcePort> sourcePorts, Clock clock) {
		this.properties = properties;
		this.sourcePorts = List.copyOf(sourcePorts);
		this.clock = clock;
	}

	/**
	 * Context evidence for the mapped incident from every bound stand source. A failing source
	 * is reported in {@code unavailable}; it is never replaced by an empty success.
	 */
	public McpEvidenceIncidentContextResponse incidentContext(
			String scenarioId,
			String incidentId,
			String scenarioRunId,
			String windowFrom,
			String windowTo
	) {
		ValidatedWindow window = validate(scenarioId, incidentId, scenarioRunId, windowFrom, windowTo);
		List<McpEvidenceRef> evidence = new ArrayList<>();
		List<McpEvidenceUnavailableSource> unavailable = new ArrayList<>();
		for (McpEvidenceSourcePort port : orderedPorts()) {
			EvidenceQuery query = query(incidentId, scenarioRunId, port.source(), window);
			try {
				evidence.add(ref(port.source(), port.read(query), query, window));
			} catch (McpEvidenceSourceException exception) {
				unavailable.add(unavailable(exception.getSource(), query.serviceKey(), exception.getAppError(),
						exception.getMessage()));
			} catch (AppException exception) {
				unavailable.add(unavailable(port.source(), query.serviceKey(), exception.getAppError(),
						exception.getMessage()));
			}
		}
		return new McpEvidenceIncidentContextResponse(
				properties.scenarioId(),
				incidentId,
				scenarioRunId,
				window.from(),
				window.to(),
				evidence,
				unavailable
		);
	}

	/**
	 * Evidence from one bound stand source. A failing source fails the call with its own
	 * application error instead of a partial result, so an unavailable source never looks like
	 * a successful empty answer.
	 */
	public McpEvidenceSourceResponse serviceSource(McpEvidenceServiceSourceRequest request) {
		if (request == null) {
			throw new AppException(AppErrors.MCP_EVIDENCE_SCENARIO_RUN_UNKNOWN);
		}
		ValidatedWindow window = validate(
				properties.scenarioId(),
				request.incidentId(),
				request.scenarioRunId(),
				request.windowFrom(),
				request.windowTo()
		);
		McpEvidenceSourcePort port = portFor(request.serviceKey());
		EvidenceQuery query = query(request.incidentId(), request.scenarioRunId(), port.source(), window);
		return new McpEvidenceSourceResponse(
				properties.scenarioId(),
				request.incidentId(),
				request.scenarioRunId(),
				port.source(),
				request.serviceKey(),
				window.from(),
				window.to(),
				ref(port.source(), port.read(query), query, window)
		);
	}

	private ValidatedWindow validate(
			String scenarioId,
			String incidentId,
			String scenarioRunId,
			String windowFrom,
			String windowTo
	) {
		requireMapped(scenarioId, incidentId, scenarioRunId);
		return validateWindow(windowFrom, windowTo);
	}

	private void requireMapped(String scenarioId, String incidentId, String scenarioRunId) {
		if (!properties.isMapped() || !properties.scenarioId().equals(scenarioId)) {
			throw new AppException(
					AppErrors.MCP_EVIDENCE_DEMO_SCENARIO_UNKNOWN,
					Map.of("scenarioId", scenarioId == null ? "" : scenarioId)
			);
		}
		if (!properties.incidentId().equals(incidentId)) {
			throw new AppException(
					AppErrors.MCP_EVIDENCE_INCIDENT_NOT_MAPPED,
					Map.of("incidentId", incidentId == null ? "" : incidentId)
			);
		}
		if (!properties.scenarioRunId().equals(scenarioRunId)) {
			throw new AppException(AppErrors.MCP_EVIDENCE_SCENARIO_RUN_UNKNOWN);
		}
	}

	/**
	 * Rules, in order: both instants must be strict ISO-8601 with microsecond or coarser
	 * precision (nothing is rounded, so no record just outside the request can slip in), the
	 * window must be non-empty and at most {@code max-window} long, and it must lie inside the
	 * observation window. Both windows are inclusive.
	 */
	private ValidatedWindow validateWindow(String windowFrom, String windowTo) {
		Instant from = parseInstant(windowFrom);
		Instant to = parseInstant(windowTo);
		long maxHours = properties.maxWindow().toHours();
		if (from == null || to == null || !from.isBefore(to)) {
			throw invalidWindow(from, to, maxHours);
		}
		Instant precision = from.truncatedTo(ChronoUnit.MICROS);
		if (!precision.equals(from) || !to.equals(to.truncatedTo(ChronoUnit.MICROS))) {
			throw invalidWindow(from, to, maxHours);
		}
		if (to.isAfter(from.plus(properties.maxWindow()))) {
			throw invalidWindow(from, to, maxHours);
		}
		McpEvidenceDemoProperties.Window observation = properties.observationWindow(clock.instant());
		if (observation == null || from.isBefore(observation.from()) || to.isAfter(observation.to())) {
			throw new AppException(AppErrors.MCP_EVIDENCE_WINDOW_OUTSIDE_DEMO);
		}
		return new ValidatedWindow(from, to);
	}

	private static AppException invalidWindow(Instant from, Instant to, long maxHours) {
		return new AppException(
				AppErrors.MCP_EVIDENCE_WINDOW_INVALID,
				Map.of(
						"from", from == null ? "missing or unparsable" : from.toString(),
						"to", to == null ? "missing or unparsable" : to.toString(),
						"maxHours", maxHours
				)
		);
	}

	private static Instant parseInstant(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Instant.parse(value.trim());
		} catch (DateTimeParseException exception) {
			return null;
		}
	}

	private McpEvidenceSourcePort portFor(String serviceKey) {
		for (McpEvidenceSourcePort port : orderedPorts()) {
			if (port.source() != McpEvidenceSource.SCENARIO && serviceKey.equals(serviceKey(port.source()))) {
				return port;
			}
		}
		throw new AppException(AppErrors.MCP_EVIDENCE_SERVICE_UNKNOWN);
	}

	private List<McpEvidenceSourcePort> orderedPorts() {
		return sourcePorts.stream()
				.filter(port -> port.source() != McpEvidenceSource.SCENARIO)
				.filter(port -> properties.hasService(serviceKey(port.source())))
				.sorted(Comparator.comparing(port -> port.source().ordinal()))
				.toList();
	}

	private String serviceKey(McpEvidenceSource source) {
		return properties.serviceKeyOf(source.name());
	}

	private EvidenceQuery query(
			String incidentId, String scenarioRunId, McpEvidenceSource source, ValidatedWindow window
	) {
		return new EvidenceQuery(incidentId, scenarioRunId, serviceKey(source), window.from(), window.to());
	}

	/**
	 * Attributes a reading to the requested incident and scenario run only when the source
	 * confirms both, and only when the observed record lies inside the requested window.
	 * Everything exposed is bounded and validated here, not trusted from the source.
	 */
	private McpEvidenceRef ref(
			McpEvidenceSource source,
			SourceReading reading,
			EvidenceQuery query,
			ValidatedWindow window
	) {
		String serviceKey = serviceKey(source);
		if (reading == null) {
			throw McpEvidenceSourceException.unavailable(source, serviceKey, null);
		}
		if (!query.incidentId().equals(reading.incidentId())
				|| !query.scenarioRunId().equals(reading.scenarioRunId())) {
			throw McpEvidenceSourceException.notAttributed(
					source, serviceKey, query.incidentId(), query.scenarioRunId());
		}
		String evidenceId = safeEvidenceId(reading.evidenceId());
		if (evidenceId == null) {
			throw McpEvidenceSourceException.rejected(source, serviceKey);
		}
		if (reading.observedAt() == null || reading.observation() == null) {
			throw McpEvidenceSourceException.unavailable(source, serviceKey, null);
		}
		if (reading.observedAt().isBefore(window.from()) || reading.observedAt().isAfter(window.to())) {
			throw McpEvidenceSourceException.outOfWindow(source, serviceKey);
		}
		SourceReading sanitized = new SourceReading(
				evidenceId,
				reading.observedAt(),
				reading.incidentId(),
				reading.scenarioRunId(),
				reading.observation()
		);
		return new McpEvidenceRef(
				evidenceId,
				source,
				query.incidentId(),
				query.scenarioRunId(),
				serviceKey,
				sanitized.observedAt(),
				window.from(),
				window.to(),
				new McpEvidenceObservation(
						sanitized.observation().message(),
						Boolean.TRUE.equals(sanitized.observation().messageTruncated()) ? Boolean.TRUE : null,
						safePattern(sanitized.observation().level(), LEVEL, MAX_LEVEL_LENGTH),
						safePattern(sanitized.observation().assignedCommand(), COMMAND, MAX_COMMAND_LENGTH),
						evidenceId,
						sanitized.observedAt().toString(),
						sanitized.observation().count(),
						sanitized.observation().value(),
						safePattern(sanitized.observation().state(), STATE, MAX_STATE_LENGTH)
				)
		);
	}

	/** The source record ID, or {@code null} when it is outside the strict bounded format. */
	static String safeEvidenceId(String evidenceId) {
		if (evidenceId == null || evidenceId.length() > MAX_EVIDENCE_ID_LENGTH
				|| !EVIDENCE_ID.matcher(evidenceId).matches()) {
			return null;
		}
		return evidenceId;
	}

	static String safePattern(String value, Pattern pattern, int maxLength) {
		return value != null && value.length() <= maxLength && pattern.matcher(value).matches() ? value : null;
	}


	private static McpEvidenceUnavailableSource unavailable(
			McpEvidenceSource source,
			String serviceKey,
			AppError error,
			String message
	) {
		return new McpEvidenceUnavailableSource(source, serviceKey, error.getCode(), message);
	}

	record ValidatedWindow(Instant from, Instant to) {
	}
}
