package ru.hardcode.dashboard.dto;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import ru.hardcode.dashboard.entity.ItsmIncidentState;

/**
 * One incident normalized from an ITSM snapshot. Dates are UTC instants, and absent
 * dates and technical placeholders are null. The original auxiliary fields are retained
 * in the normalized record map.
 */
public record ItsmIncidentRecord(
		String id,
		ItsmIncidentState state,
		int priorityId,
		Instant submitDate,
		Instant lastAssignmentToTeamDateTime,
		Instant deadline,
		Instant completionDate,
		String solution,
		Instant technicalReferenceDate,
		Map<String, Object> raw
) {

	public ItsmIncidentRecord {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("id must be a non-blank string");
		}
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(submitDate, "submitDate");
		if (state.isCompleted()) {
			Objects.requireNonNull(completionDate, "completionDate of a completed incident");
			if (solution == null || solution.isBlank()) {
				throw new IllegalArgumentException("solution of a completed incident must not be blank");
			}
		} else {
			if (completionDate != null) {
				throw new IllegalArgumentException("completionDate of an active incident must be null");
			}
			if (solution != null) {
				throw new IllegalArgumentException("solution of an active incident must be null");
			}
		}
		raw = raw == null
				? Map.of()
				: Collections.unmodifiableMap(new LinkedHashMap<>(raw));
	}

	public boolean isActive() {
		return !state.isCompleted();
	}
}
