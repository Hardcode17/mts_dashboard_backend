package ru.hardcode.dashboard.dto;

import java.time.Instant;
import java.util.List;

/**
 * One durable fact of the INC change journal (KAN-28).
 *
 * @param eventSeq       monotonic sequence number, unique across all sources and independent of
 *                       {@code changeRevision}; it never changes once committed
 * @param sourceId       the ITSM integration source that produced the observation
 * @param incidentId     the incident the fact belongs to
 * @param sourceVersion  the applied source version of the observation
 * @param changeRevision the snapshot change revision; one revision can carry many events
 * @param changeKind     {@code ADDED}, {@code UPDATED}, {@code MISSING} or {@code RETURNED};
 *                       {@code MISSING} never implies a resolution and never erases history
 * @param observedAt     the moment the backend observed and committed the change
 * @param sourceTime     the source-side time of the record, when the ITSM provides one
 * @param fields         previous and current values of every tracked field
 */
public record IncidentChangeEvent(
		long eventSeq,
		String sourceId,
		String incidentId,
		long sourceVersion,
		long changeRevision,
		String changeKind,
		Instant observedAt,
		Instant sourceTime,
		List<IncidentChangeField> fields
) {
}
