package ru.hardcode.dashboard.service.mcpdashboard;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cursor semantics of the MCP Dashboard: the same walk continues, while tampered, foreign,
 * misrouted and expired cursors are rejected with the contract codes.
 */
@ExtendWith(MockitoExtension.class)
class McpDashboardCursorsTest {

	private static final Instant NOW = Instant.parse("2026-11-22T09:00:00Z");
	private final McpDashboardCursors cursors = new McpDashboardCursors(new ObjectMapper(), "test-secret");

	@Test
	void roundTripKeepsToolCallerBindingAndPosition() {
		String cursor = cursors.encode("list_incidents", "operator", "snap-42-abcdef01", 60,
				Duration.ofMinutes(15), NOW);
		var decoded = cursors.decode(cursor, "list_incidents", "operator", "snap-42-abcdef01", NOW.plusSeconds(60));
		assertThat(decoded.position()).isEqualTo(60);
		assertThat(decoded.binding()).isEqualTo("snap-42-abcdef01");
		assertThat(decoded.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
	}

	@Test
	void tamperedPayloadIsRejectedAsInvalidCursor() {
		String cursor = cursors.encode("list_incidents", "operator", "snap-42-abcdef01", 0,
				Duration.ofMinutes(15), NOW);
		String payloadPart = cursor.substring(0, cursor.indexOf('.'));
		// Flip one character of the payload without touching the signature.
		char flipped = payloadPart.charAt(4) == 'A' ? 'B' : 'A';
		String tampered = payloadPart.substring(0, 4) + flipped + payloadPart.substring(5)
				+ cursor.substring(cursor.indexOf('.'));
		assertThatThrownBy(() -> cursors.decode(tampered, "list_incidents", "operator", "snap-42-abcdef01", NOW))
				.isInstanceOf(McpDashboardException.class)
				.satisfies(exception -> assertThat(((McpDashboardException) exception).code())
						.isEqualTo(McpDashboardException.Code.INVALID_CURSOR));
	}

	@Test
	void foreignCallerIsRejected() {
		String cursor = cursors.encode("list_incidents", "operator", "snap-42-abcdef01", 0,
				Duration.ofMinutes(15), NOW);
		assertThatThrownBy(() -> cursors.decode(cursor, "list_incidents", "someone-else", "snap-42-abcdef01", NOW))
				.isInstanceOf(McpDashboardException.class)
				.satisfies(exception -> assertThat(((McpDashboardException) exception).code())
						.isEqualTo(McpDashboardException.Code.INVALID_CURSOR));
	}

	@Test
	void anotherToolOrBindingIsRejected() {
		String cursor = cursors.encode("find_assignees", "operator", "assignees:INC000001", 3,
				Duration.ofMinutes(15), NOW);
		assertThatThrownBy(() -> cursors.decode(cursor, "list_incidents", "operator", "assignees:INC000001", NOW))
				.isInstanceOf(McpDashboardException.class);
		assertThatThrownBy(() -> cursors.decode(cursor, "find_assignees", "operator", "groups:INC000001", NOW))
				.isInstanceOf(McpDashboardException.class);
	}

	@Test
	void expiredCursorIsCursorExpiredAndAsksToResync() {
		String cursor = cursors.encode("list_incident_changes", "operator", "epoch", 100,
				Duration.ofSeconds(10), NOW);
		assertThatThrownBy(() -> cursors.decode(cursor, "list_incident_changes", "operator", "epoch",
				NOW.plusSeconds(11)))
				.isInstanceOf(McpDashboardException.class)
				.satisfies(exception -> {
					var expired = (McpDashboardException) exception;
					assertThat(expired.code()).isEqualTo(McpDashboardException.Code.CURSOR_EXPIRED);
					assertThat(expired.wireRecovery()).isEqualTo("resync_snapshot");
				});
	}

	@Test
	void garbageIsInvalidCursor() {
		assertThatThrownBy(() -> cursors.decode("garbage", "list_incidents", "operator", "binding", NOW))
				.isInstanceOf(McpDashboardException.class);
	}
}
