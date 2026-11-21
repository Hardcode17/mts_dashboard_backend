package ru.hardcode.dashboard.entity;

/**
 * The {@code stateEnum} values present in the original ITSM snapshot. Values are matched
 * exactly; {@link #isCompleted()} states must carry a completion date and solution.
 */
public enum ItsmIncidentState {
	Assigned(false),
	WorkInProgress(false),
	Resolved(true),
	Closed(true),
	Research(true);

	private final boolean completed;

	ItsmIncidentState(boolean completed) {
		this.completed = completed;
	}

	public boolean isCompleted() {
		return completed;
	}
}
