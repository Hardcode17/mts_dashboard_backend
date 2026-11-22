package ru.hardcode.dashboard.configuration.mcpdashboard;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * Settings of the read-only MCP Dashboard adapter (KAN-86, contract v1 from
 * {@code Hardcode17/mts_dashboard_data/docs/mcp}).
 *
 * @param client            machine-agent identity allowed to call the tools; a human
 *                          coordinator JWT is accepted through the coordinator grant instead
 * @param cursorSecret      HMAC secret signing every MCP cursor; blank keeps cursors invalid
 * @param snapshotFresh     age of the published snapshot that still counts as {@code fresh}
 * @param cursorTtl         lifetime of snapshot-bound cursors (list/similar/assignees pages)
 * @param changesCursorTtl  lifetime of the journal replay cursor, matching the KAN-28
 *                          retention promise of at least seven days
 */
@ConfigurationProperties(prefix = "mcp.dashboard")
public record McpDashboardProperties(
		Client client,
		String cursorSecret,
		Duration snapshotFresh,
		Duration cursorTtl,
		Duration changesCursorTtl
) {

	private static final String DEFAULT_ROLE = "mcp:dashboard:read";
	private static final Duration DEFAULT_SNAPSHOT_FRESH = Duration.ofSeconds(60);
	private static final Duration DEFAULT_CURSOR_TTL = Duration.ofMinutes(15);
	private static final Duration DEFAULT_CHANGES_CURSOR_TTL = Duration.ofDays(7);

	public McpDashboardProperties {
		client = client == null ? new Client(null, null, null) : client;
		snapshotFresh = snapshotFresh == null ? DEFAULT_SNAPSHOT_FRESH : snapshotFresh;
		cursorTtl = cursorTtl == null ? DEFAULT_CURSOR_TTL : cursorTtl;
		changesCursorTtl = changesCursorTtl == null ? DEFAULT_CHANGES_CURSOR_TTL : changesCursorTtl;
	}

	public boolean cursorsConfigured() {
		return StringUtils.hasText(cursorSecret);
	}

	public Duration cursorTtl(boolean journalReplay) {
		return journalReplay ? changesCursorTtl : cursorTtl;
	}

	/**
	 * Identity of the machine agent. The MVP agent is read-only: it never receives ITSM
	 * write scopes and {@code assign_incident} is not declared in {@code tools/list}.
	 *
	 * @param audience required JWT audience of the machine client
	 * @param clientId required {@code azp} claim
	 * @param role     role the machine client must hold
	 */
	@ConfigurationProperties(prefix = "mcp.dashboard.client")
	public record Client(String audience, String clientId, String role) {

		public Client {
			role = role == null || role.isBlank() ? DEFAULT_ROLE : role;
		}

		public boolean isConfigured() {
			return StringUtils.hasText(audience) && StringUtils.hasText(clientId);
		}
	}
}
