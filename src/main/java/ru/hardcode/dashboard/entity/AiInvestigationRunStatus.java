package ru.hardcode.dashboard.entity;

/**
 * Lifecycle of one AI investigation run. The values are stored and published lowercase,
 * like every status of this API.
 */
public enum AiInvestigationRunStatus {
	RUNNING("running"),
	COMPLETED("completed"),
	FAILED("failed");

	private final String code;

	AiInvestigationRunStatus(String code) {
		this.code = code;
	}

	/** The stored and published value of this status. */
	public String code() {
		return code;
	}

	public static AiInvestigationRunStatus of(String code) {
		for (AiInvestigationRunStatus status : values()) {
			if (status.code.equals(code)) {
				return status;
			}
		}
		throw new IllegalArgumentException("Unknown investigation run status " + code);
	}
}
