package ru.hardcode.dashboard.service.deadlinerisk;

/**
 * The deadline risk category of one incident (KAN-82). {@link #UNKNOWN} is not a scored level:
 * it reports that the assessment is unavailable — no deadline, no group history or no pass yet —
 * and never blocks the card.
 */
public enum DeadlineRiskLevel {
	LOW("low"),
	MEDIUM("medium"),
	HIGH("high"),
	UNKNOWN("unknown");

	private final String wireName;

	DeadlineRiskLevel(String wireName) {
		this.wireName = wireName;
	}

	/** The API value of the category, as fixed by the KAN-82 contract. */
	public String wireName() {
		return wireName;
	}

	public static DeadlineRiskLevel ofWire(String wireName) {
		for (DeadlineRiskLevel level : values()) {
			if (level.wireName.equals(wireName)) {
				return level;
			}
		}
		throw new IllegalArgumentException("Unknown deadline risk category: " + wireName);
	}
}
