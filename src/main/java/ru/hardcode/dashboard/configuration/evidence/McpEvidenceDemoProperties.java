package ru.hardcode.dashboard.configuration.evidence;

import java.time.Duration;
import java.time.Instant;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Explicit configuration of the single bounded demo scenario served by the read-only MCP
 * evidence tools. Nothing here is discovered at runtime: the incident, the scenario run, the
 * bound services and the stand sources are mapped by identifier in configuration.
 *
 * <p>When {@code scenario-id}, {@code incident-id}, {@code scenario-run-id} or either bound
 * service key is missing, no demo mapping exists and every evidence tool fails closed.
 */
@ConfigurationProperties(prefix = "mcp.evidence.demo")
public record McpEvidenceDemoProperties(
		String scenarioId,
		String incidentId,
		String scenarioRunId,
		String itsmServiceKey,
		String logServiceKey,
		String databaseServiceKey,
		Duration maxWindow,
		Window observationWindow,
		Duration observationWindowAge,
		ItsmSource itsm,
		LogSource log,
		DatabaseSource database
) {

	/** A configured observation window; both bounds must be set to be usable. */
	public record Window(Instant from, Instant to) {
	}

	/**
	 * Mock ITSM source holding stand incidents. The assigned command comes from the fixed
	 * {@code GET /api/incidents/{incidentId}} record; its address is a listener name the
	 * evidence services map to a command.
	 */
	public record ItsmSource(String baseUrl) {
	}

	/**
	 * Log source bound to {@code logServiceKey}. The customer identifier may be shared by two
	 * incidents, so every record must also name the incident and scenario run it belongs to;
	 * the source is asked only for records of the mapped incident and run.
	 */
	public record LogSource(String baseUrl, String customerId) {
	}

	/**
	 * Read-only diagnostics database. The view is a fixed stand view; only fixed queries run
	 * against it.
	 */
	public record DatabaseSource(String url, String username, String password, String view) {
	}

	public McpEvidenceDemoProperties {
		maxWindow = maxWindow == null ? Duration.ofHours(24) : maxWindow;
		observationWindowAge = observationWindowAge == null || observationWindowAge.isNegative()
				|| observationWindowAge.isZero() ? Duration.ofHours(24) : observationWindowAge;
	}

	public boolean isMapped() {
		return hasText(scenarioId)
				&& hasText(incidentId)
				&& hasText(scenarioRunId)
				&& hasText(itsmServiceKey)
				&& hasText(logServiceKey)
				&& hasText(databaseServiceKey);
	}

	public boolean hasService(String serviceKey) {
		return hasText(serviceKey)
				&& (serviceKey.equals(itsmServiceKey)
				|| serviceKey.equals(logServiceKey)
				|| serviceKey.equals(databaseServiceKey));
	}

	/** The bound service key of one stand source; {@code SCENARIO} has none. */
	public String serviceKeyOf(String source) {
		return switch (source) {
			case "ITSM" -> itsmServiceKey;
			case "LOG" -> logServiceKey;
			case "DATABASE" -> databaseServiceKey;
			default -> null;
		};
	}

	/**
	 * The only window a call may observe. A configured window is fixed; otherwise it is the
	 * last {@code observation-window-age} before {@code now}, limited to {@code max-window}.
	 * Each requested window is also limited separately. A {@code null}
	 * window means that nothing may be observed.
	 */
	public Window observationWindow(Instant now) {
		if (observationWindow != null && (observationWindow.from() != null || observationWindow.to() != null)) {
			var from = observationWindow.from();
			var to = observationWindow.to();
			return from != null && to != null && from.isBefore(to) ? observationWindow : null;
		}
		Duration age = observationWindowAge.compareTo(maxWindow) <= 0 ? observationWindowAge : maxWindow;
		return new Window(now.minus(age), now);
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
}
