package ru.hardcode.dashboard.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One significant event of the shift (KAN-25). {@code reasons} holds every code matched by the
 * single INC update; {@code ackRequired} separates the alarm events that KAN-29 will let the
 * shift confirm with «Увидел»; {@code incident} is the notification data of the INC after the
 * change (number, product, state, deadline, team, solution).
 *
 * @param eventId       stable id, {@code evt-} plus at least four digits, assigned once
 * @param eventSeq      queue position for {@code sinceEventSeq} polling
 * @param changeRevision journal revision of the INC change that produced the event
 */
public record ShiftEventResponse(
		String eventId,
		long eventSeq,
		String incidentId,
		List<String> reasons,
		boolean ackRequired,
		long changeRevision,
		String sourceId,
		Instant occurredAt,
		Map<String, Object> incident
) {
}
