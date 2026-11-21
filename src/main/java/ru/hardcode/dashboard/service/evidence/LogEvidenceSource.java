package ru.hardcode.dashboard.service.evidence;

import ru.hardcode.dashboard.configuration.evidence.ConditionalOnEvidenceMapping;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import ru.hardcode.dashboard.configuration.evidence.McpEvidenceDemoProperties;
import ru.hardcode.dashboard.dto.McpEvidenceSource;

/**
 * Latest log record of the mapped incident inside the evidence window from the controlled
 * stand log source. The query is a fixed bounded read: customer id, incident id, scenario run
 * id, {@code from}, {@code to} and {@code limit=1}.
 *
 * <p>The customer id may be shared by two incidents, so a record is attributed only when the
 * same response also names the mapped incident <em>and</em> scenario run. A record of another
 * incident, or one that names no owner, is never used; the latest attributable record is
 * chosen. The message is user-controlled stand data: it is sanitized and truncated to a safe
 * length and obvious secrets are redacted before it leaves the service.
 */
@Component
@ConditionalOnEvidenceMapping
public class LogEvidenceSource implements McpEvidenceSourcePort {

	static final String QUERY_PATH = "/api/logs";
	static final int MAX_MESSAGE_LENGTH = 200;

	private static final Pattern RECORD_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");
	private static final Pattern SENSITIVE = Pattern.compile(
			"(?i)(bearer\\s+)[^\\s,;]+"
					+ "|((?:password|passwd|pwd|secret|token|api[_-]?key|authorization|cookie)\\s*[=:]\\s*)[^\\s,;]+"
					+ "|(\\b(?:\\d[ -]?){12,18}\\d\\b)"
	);
	private static final String REDACTED = "[redacted]";

	private final RestClient restClient;
	private final McpEvidenceDemoProperties properties;

	public LogEvidenceSource(
			@Qualifier("evidenceRestClient") RestClient restClient,
			McpEvidenceDemoProperties properties
	) {
		this.restClient = restClient;
		this.properties = properties;
	}

	@Override
	public McpEvidenceSource source() {
		return McpEvidenceSource.LOG;
	}

	@Override
	public SourceReading read(EvidenceQuery query) {
		McpEvidenceDemoProperties.LogSource source = properties.log();
		if (source == null || source.baseUrl() == null || source.baseUrl().isBlank()
				|| source.customerId() == null || source.customerId().isBlank()) {
			throw McpEvidenceSourceException.unavailable(source(), query.serviceKey(), null);
		}

		List<LogRecord> records;
		try {
			records = restClient.get()
					.uri(normalizedBaseUrl(source.baseUrl()) + QUERY_PATH
									+ "?customerId={customerId}&incidentId={incidentId}&scenarioRunId={scenarioRunId}"
									+ "&from={from}&to={to}&limit=1",
							source.customerId(),
							query.incidentId(),
							query.scenarioRunId(),
							query.from().toString(),
							query.to().toString())
					.accept(MediaType.APPLICATION_JSON)
					.retrieve()
					.body(new ParameterizedTypeReference<>() {
					});
		} catch (RestClientResponseException exception) {
			throw McpEvidenceSourceException.rejected(source(), query.serviceKey());
		} catch (RestClientException exception) {
			throw McpEvidenceSourceException.unavailable(source(), query.serviceKey(), exception);
		}

		LogRecord latest = records == null
				? null
				: records.stream()
						.filter(record -> belongsTo(record, query))
						.filter(record -> insideWindow(record, query))
						.max((left, right) -> parse(left.timestamp()).compareTo(parse(right.timestamp())))
						.orElse(null);
		if (latest == null) {
			throw McpEvidenceSourceException.unavailable(source(), query.serviceKey(), null);
		}

		String rawMessage = latest.message() == null ? "" : latest.message();
		String safeMessage = safeMessage(rawMessage);
		return new SourceReading(
				"log:" + latest.id(),
				parse(latest.timestamp()),
				latest.incidentId(),
				latest.scenarioRunId(),
				new Observation(
						safeMessage,
						!safeMessage.equals(rawMessage),
						safeLevel(latest.level()),
						null,
						null,
						null,
						null
				)
		);
	}

	/** A record belongs to the query only when it carries both explicit identifiers. */
	private static boolean belongsTo(LogRecord record, EvidenceQuery query) {
		return record != null
				&& record.id() != null
				&& RECORD_ID.matcher(record.id()).matches()
				&& query.incidentId().equals(record.incidentId())
				&& query.scenarioRunId().equals(record.scenarioRunId())
				&& parse(record.timestamp()) != null;
	}

	private static boolean insideWindow(LogRecord record, EvidenceQuery query) {
		Instant observedAt = parse(record.timestamp());
		return observedAt != null && !observedAt.isBefore(query.from()) && !observedAt.isAfter(query.to());
	}

	/**
	 * Single line, bounded and secret-free. Only configured stand logs reach this point;
	 * authorization values, credential pairs and long card-like digit runs are redacted, and
	 * the message is cut at a word boundary within the safe length.
	 */
	static String safeMessage(String message) {
		String singleLine = message.replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}]+", " ").replaceAll(" {2,}", " ").trim();
		String redacted = SENSITIVE.matcher(singleLine)
				.replaceAll(match -> match.group(1) != null ? match.group(1) + REDACTED
						: match.group(2) != null ? match.group(2) + REDACTED
						: REDACTED);
		if (redacted.length() <= MAX_MESSAGE_LENGTH) {
			return redacted;
		}
		int cut = redacted.lastIndexOf(' ', MAX_MESSAGE_LENGTH);
		return redacted.substring(0, cut < MAX_MESSAGE_LENGTH / 2 ? MAX_MESSAGE_LENGTH : cut).trim();
	}

	static String safeLevel(String level) {
		if (level == null) {
			return null;
		}
		String candidate = level.trim().toUpperCase(Locale.ROOT);
		return candidate.matches("[A-Z]{1,8}") ? candidate : null;
	}

	static Instant parse(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Instant.parse(value.trim());
		} catch (RuntimeException exception) {
			return null;
		}
	}

	private static String normalizedBaseUrl(String baseUrl) {
		String trimmed = baseUrl.trim();
		return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
	}

	/**
	 * One record of the stand log source. {@code incidentId} and {@code scenarioRunId} are the
	 * ownership fields the source must return for the record to be usable as evidence.
	 */
	record LogRecord(
			String id,
			String timestamp,
			String message,
			String level,
			String incidentId,
			String scenarioRunId
	) {
	}
}
