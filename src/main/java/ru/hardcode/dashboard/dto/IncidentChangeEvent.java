package ru.hardcode.dashboard.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One durable fact of the INC change journal (KAN-28).
 *
 * @param eventSeq       monotonic sequence number, unique across all sources and independent of
 *                       {@code changeRevision}; it never changes once committed
 * @param sourceId       the ITSM integration source, or null for a local event
 * @param incidentId     the incident the fact belongs to
 * @param sourceVersion  the applied ITSM version, or null for a local event
 * @param changeRevision the snapshot change revision; one revision can carry many events
 * @param changeKind     {@code ADDED}, {@code UPDATED}, {@code MISSING} or {@code RETURNED};
 *                       {@code MISSING} never implies a resolution and never erases history
 * @param observedAt     the moment the backend observed and committed the change
 * @param sourceTime     the source-side time of the record, when the ITSM provides one
 * @param eventId        stable local event ID, omitted for an ITSM change
 * @param details        local event payload, omitted for an ITSM change
 * @param fields         previous and current values for an ITSM change
 */
public record IncidentChangeEvent(
		long eventSeq,
		String sourceId,
		String incidentId,
		Long sourceVersion,
		long changeRevision,
		String changeKind,
		Instant observedAt,
		Instant sourceTime,
		List<IncidentChangeField> fields,
		@JsonInclude(JsonInclude.Include.NON_NULL) String eventId,
		@JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> details
) {
}
