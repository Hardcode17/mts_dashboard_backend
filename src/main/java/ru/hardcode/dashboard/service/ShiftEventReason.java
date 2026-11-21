package ru.hardcode.dashboard.service;

/**
 * One reason a journaled INC change becomes a significant shift event (KAN-25). The reasons
 * matched by a single update are merged into one queue event; the codes are stable values of
 * the {@code reasons} array in {@code shift_event} and of the queue HTTP response.
 *
 * <p>{@code requiresAcknowledgement} marks the three alarm signals that switch the common
 * screen to the fullscreen notification and that the shift confirms with «Увидел» (KAN-29
 * acknowledges exactly the shown eventIds). Entering the coordinators and an explicit
 * resolution of a critical are queue signals for notifications (KAN-35), not alarms.
 */
public enum ShiftEventReason {

	/** A newly observed incident that is already active with priorityId 4. */
	NEW_ACTIVE_CRITICAL("new_critical", true),

	/** An active incident raised to priorityId 4. */
	ESCALATED_TO_CRITICAL("became_critical", true),

	/** A critical incident returned to an active state from a completed one. */
	CRITICAL_REOPENED("critical_reopened", true),

	/** The incident entered the ITSM group of mass-incident coordinators. */
	ENTERED_COORDINATORS("assigned_to_coordinators", false),

	/** An explicit Resolved of a critical incident while it was active. */
	CRITICAL_RESOLVED("critical_resolved", false);

	private final String code;
	private final boolean alarm;

	ShiftEventReason(String code, boolean alarm) {
		this.code = code;
		this.alarm = alarm;
	}

	/** Stable machine code stored in the queue and returned to consumers. */
	public String code() {
		return code;
	}

	/** True for the alarm reasons whose events require «Увидел». */
	public boolean requiresAcknowledgement() {
		return alarm;
	}
}
