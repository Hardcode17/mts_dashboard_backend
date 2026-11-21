package ru.hardcode.dashboard.dto;

import java.time.Instant;
import java.util.List;

/**
 * The journal of one incident (KAN-28). The journal is readable even after the incident
 * disappeared from the source: {@code lastObservedAt} is the moment of its last observed
 * fact, including the {@code MISSING} one.
 */
public record IncidentHistoryResponse(String incidentId, Instant lastObservedAt,
		List<IncidentChangeEvent> events) {
}
