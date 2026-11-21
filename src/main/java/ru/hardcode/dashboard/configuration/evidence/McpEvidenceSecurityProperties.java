package ru.hardcode.dashboard.configuration.evidence;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Identity of the MCP client allowed to read the bounded demo evidence. It is the existing
 * ITSM integration machine client, which the gateway authenticates before it forwards the
 * request; the machine client ID and role are revalidated here. Blank configuration keeps
 * the evidence transport closed.
 *
 * @param audience required JWT audience of the integration machine client
 * @param clientId required {@code azp} claim
 * @param role role the machine client must hold
 */
@ConfigurationProperties(prefix = "mcp.evidence.client")
public record McpEvidenceSecurityProperties(String audience, String clientId, String role) {

	public McpEvidenceSecurityProperties {
		role = role == null || role.isBlank() ? "itsm:evidence:read" : role;
	}

	public boolean isConfigured() {
		return hasText(audience) && hasText(clientId);
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
}
