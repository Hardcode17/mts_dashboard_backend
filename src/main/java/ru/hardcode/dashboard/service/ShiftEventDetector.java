package ru.hardcode.dashboard.service;

import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ru.hardcode.dashboard.service.ItsmIncidentChangeFeed.ChangeKind;
import ru.hardcode.dashboard.service.ItsmIncidentChangeFeed.IncidentChange;

/**
 * KAN-25 rules that turn one journaled INC change into the reasons of a single shift event.
 * Pure and stateless: the caller owns the cursor, the baseline and persistence.
 *
 * <p>A change without a matching rule raises no signal — a description-only edit, a vanished
 * row or a completed-state shuffle that is none of the five significant transitions. The
 * journal always carries the normalized record, so the rules read the original fields:
 * {@code stateEnum}, {@code priorityId} and {@code assignedGroupName}.
 */
public final class ShiftEventDetector {

	/** The ITSM group of mass-incident coordinators; the same value as the dashboard groups. */
	static final String COORDINATOR_GROUP = "КЦ ИТ Координаторы Массовых Инцидентов";

	private static final String STATE_RESOLVED = "Resolved";
	private static final Set<String> ACTIVE_STATES = Set.of("Assigned", "WorkInProgress");
	private static final Set<String> COMPLETED_STATES = Set.of("Resolved", "Closed", "Research");
	private static final int CRITICAL_PRIORITY = 4;

	private ShiftEventDetector() {
	}

	/**
	 * Reasons of one journaled change, in canonical {@link ShiftEventReason} order. At most one
	 * queue event is created for the change; all matched reasons are merged into it.
	 */
	public static List<ShiftEventReason> reasons(IncidentChange change) {
		if (change.after() == null) {
			// A row that vanished from the full snapshot never raises a shift signal.
			return List.of();
		}
		boolean afterActive = isActive(change.after());
		boolean afterCritical = isCritical(change.after());
		var reasons = EnumSet.noneOf(ShiftEventReason.class);
		switch (change.kind()) {
			// ADDED and RETURNED carry no usable before: the row is a fresh observation, so it
			// can raise new_critical / assigned_to_coordinators but never the transition rules.
			case ADDED, RETURNED -> {
				if (afterActive && afterCritical) {
					reasons.add(ShiftEventReason.NEW_ACTIVE_CRITICAL);
				}
				if (isOnCoordinators(change.after())) {
					reasons.add(ShiftEventReason.ENTERED_COORDINATORS);
				}
			}
			case UPDATED -> {
				if (change.before() == null) {
					break;
				}
				boolean beforeCritical = isCritical(change.before());
				if (!beforeCritical && afterCritical && afterActive) {
					reasons.add(ShiftEventReason.ESCALATED_TO_CRITICAL);
				}
				if (isCompleted(change.before()) && afterActive && (beforeCritical || afterCritical)) {
					reasons.add(ShiftEventReason.CRITICAL_REOPENED);
				}
				if (!isOnCoordinators(change.before()) && isOnCoordinators(change.after())) {
					reasons.add(ShiftEventReason.ENTERED_COORDINATORS);
				}
				if (isActive(change.before()) && STATE_RESOLVED.equals(change.after().get("stateEnum"))
						&& (beforeCritical || afterCritical)) {
					reasons.add(ShiftEventReason.CRITICAL_RESOLVED);
				}
			}
			case MISSING -> {
				// Vanishing is not one of the five significant transitions.
			}
		}
		return reasons.stream().sorted(Comparator.comparingInt(ShiftEventReason::ordinal)).toList();
	}

	/** True when at least one reason of the change is an alarm that requires «Увидел». */
	public static boolean requiresAcknowledgement(Collection<ShiftEventReason> reasons) {
		return reasons.stream().anyMatch(ShiftEventReason::requiresAcknowledgement);
	}

	/** Notification data of the INC after the change: number, product, state, deadline, team, solution. */
	public static Map<String, Object> notificationPayload(Map<String, Object> after) {
		// LinkedHashMap keeps a stable field order in the queue response and in the payload column.
		var payload = new java.util.LinkedHashMap<String, Object>(12);
		payload.put("id", after.get("id"));
		payload.put("header", after.get("header"));
		payload.put("productName", after.get("productName"));
		payload.put("stateEnum", after.get("stateEnum"));
		payload.put("priorityId", after.get("priorityId"));
		payload.put("submitDate", after.get("submitDate"));
		payload.put("deadline", after.get("deadline"));
		payload.put("completionDate", after.get("completionDate"));
		payload.put("solution", after.get("solution"));
		payload.put("assignedGroupName", after.get("assignedGroupName"));
		payload.put("assignedEmployee", after.get("assignedEmployee"));
		return payload;
	}

	private static boolean isActive(Map<String, Object> record) {
		return ACTIVE_STATES.contains(record.get("stateEnum"));
	}

	private static boolean isCompleted(Map<String, Object> record) {
		return COMPLETED_STATES.contains(record.get("stateEnum"));
	}

	private static boolean isCritical(Map<String, Object> record) {
		return Integer.valueOf(CRITICAL_PRIORITY).equals(record.get("priorityId"));
	}

	private static boolean isOnCoordinators(Map<String, Object> record) {
		return COORDINATOR_GROUP.equals(record.get("assignedGroupName"));
	}
}
