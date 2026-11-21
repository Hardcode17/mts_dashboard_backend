package ru.hardcode.dashboard.dto;

import java.time.Instant;

import ru.hardcode.dashboard.entity.ItsmIncidentState;

/**
 * One current incident of the installed ITSM baseline. {@code productId} and {@code serviceId}
 * are application catalog ids; {@code assignedPersonId} is an external ITSM person and never
 * a local application account.
 */
public record ItsmIncidentResponse(
		String id,
		ItsmIncidentState state,
		int priorityId,
		Instant submitDate,
		Instant deadline,
		Instant completionDate,
		String productId,
		String serviceId,
		String assignedPersonId
) {
}
