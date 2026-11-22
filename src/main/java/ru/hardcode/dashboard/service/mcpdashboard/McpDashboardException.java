package ru.hardcode.dashboard.service.mcpdashboard;

/**
 * A contract-level failure of one MCP tool call (KAN-86). Carries exactly the four typed
 * fields of the v1 error object; the HTTP status stays with the transport (401/403), while
 * tool calls turn this exception into {@code ok=false} with {@code isError=true}.
 *
 * @param code      stable contract code, e.g. {@code NOT_FOUND}
 * @param message   public message without internals, 1-500 characters
 * @param retryable whether repeating the same call can succeed
 * @param recovery  suggested client action
 */
public final class McpDashboardException extends RuntimeException {

	public enum Code {
		INVALID_ARGUMENT,
		NOT_FOUND,
		ACCESS_DENIED,
		SOURCE_UNAVAILABLE,
		CURSOR_EXPIRED,
		INVALID_CURSOR,
		RATE_LIMITED,
		UNSUPPORTED_FILTER
	}

	public enum Recovery {
		NONE,
		RETRY,
		RESYNC_SNAPSHOT,
		REAUTHENTICATE
	}

	private final Code code;
	private final boolean retryable;
	private final Recovery recovery;

	private McpDashboardException(Code code, String message, boolean retryable, Recovery recovery, Throwable cause) {
		super(message, cause);
		this.code = code;
		this.retryable = retryable;
		this.recovery = recovery;
	}

	public static McpDashboardException invalidArgument(String message) {
		return new McpDashboardException(Code.INVALID_ARGUMENT, message, false, Recovery.NONE, null);
	}

	public static McpDashboardException notFound(String message) {
		return new McpDashboardException(Code.NOT_FOUND, message, false, Recovery.NONE, null);
	}

	public static McpDashboardException unsupportedFilter(String message) {
		return new McpDashboardException(Code.UNSUPPORTED_FILTER, message, false, Recovery.NONE, null);
	}

	public static McpDashboardException invalidCursor(String message) {
		return new McpDashboardException(Code.INVALID_CURSOR, message, false, Recovery.NONE, null);
	}

	public static McpDashboardException cursorExpired(String message) {
		return new McpDashboardException(Code.CURSOR_EXPIRED, message, false, Recovery.RESYNC_SNAPSHOT, null);
	}

	public static McpDashboardException sourceUnavailable(String message) {
		return new McpDashboardException(Code.SOURCE_UNAVAILABLE, message, true, Recovery.RETRY, null);
	}

	public Code code() {
		return code;
	}

	public boolean retryable() {
		return retryable;
	}

	public Recovery recovery() {
		return recovery;
	}

	public String wireCode() {
		return code.name();
	}

	public String wireRecovery() {
		return recovery == Recovery.NONE ? "none"
				: recovery == Recovery.RETRY ? "retry"
				: recovery == Recovery.RESYNC_SNAPSHOT ? "resync_snapshot"
				: "reauthenticate";
	}
}
