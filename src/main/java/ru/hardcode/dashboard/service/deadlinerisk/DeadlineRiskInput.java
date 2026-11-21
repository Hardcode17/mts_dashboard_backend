package ru.hardcode.dashboard.service.deadlinerisk;

import java.time.Duration;
import java.time.Instant;

/**
 * The feature values of one incident read at evaluation time (KAN-82). Features only observe the
 * published state and the change journal; nothing here feeds back into ITSM.
 *
 * @param assignedGroupName      current resolution group; without it no group median exists
 * @param deadline               the ITSM deadline, {@code null} when the card has none
 * @param evaluatedAt            the moment of this assessment
 * @param medianResolutionHours median resolution time of completed incidents of the group
 *                               (service × group × priority first, then group × priority and
 *                               group-wide fallbacks), {@code null} or
 *                               non-positive when there is no usable history
 * @param deadlineExtensions     how often the deadline changed before this assessment
 * @param reassignments          how often the group or assignee changed before this assessment
 * @param reopenings             how often the card returned from a completed state
 * @param timeSinceStatusChange  time since the last observed status change (registration fallback)
 * @param assigneeOpenIncidents  open incidents currently assigned to the same person
 * @param priorityId             the original ITSM priority (1–4), never substituted
 */
public record DeadlineRiskInput(
		String assignedGroupName,
		Instant deadline,
		Instant evaluatedAt,
		Double medianResolutionHours,
		long deadlineExtensions,
		long reassignments,
		long reopenings,
		Duration timeSinceStatusChange,
		long assigneeOpenIncidents,
		int priorityId
) {
}
