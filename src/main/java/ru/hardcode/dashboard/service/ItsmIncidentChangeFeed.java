package ru.hardcode.dashboard.service;

import java.util.List;
import java.util.Map;

/**
 * Internal read contract over the journaled INC changes: rows ordered by their change
 * revision, strictly after a cursor revision. KAN-25 shift-event detection consumes only
 * this shape and never the storage behind it.
 *
 * <p>This is the port that KAN-28 («записать изменения INC и выдать историю по курсору»)
 * closes with its cursor-based history implementation. Until that lands,
 * {@code JdbcItsmIncidentChangeFeed} reads the same journal table directly, so detection
 * already works on fixtures; swapping the adapter behind this port changes neither the
 * detection rules, nor the eventIds, nor the queue contract.
 */
public interface ItsmIncidentChangeFeed {

	/**
	 * Reads journaled changes with a revision strictly greater than {@code afterRevision},
	 * in ascending revision order (ties broken deterministically by source and incident id).
	 *
	 * @param afterRevision cursor revision; {@code 0} reads from the beginning
	 * @param limit maximum number of returned rows
	 */
	List<IncidentChange> afterRevision(long afterRevision, int limit);

	/**
	 * One journaled change of one INC. {@code before} is {@code null} for a first observation
	 * ({@code ADDED}) or a row that came back after vanishing ({@code RETURNED});
	 * {@code after} is {@code null} when the row vanished from the full snapshot.
	 *
	 * @param revision      journal change revision, monotonically assigned per publication
	 * @param sourceVersion delivery version of the snapshot that produced the change
	 */
	record IncidentChange(long revision, String sourceId, long sourceVersion, String incidentId,
			ChangeKind kind, Map<String, Object> before, Map<String, Object> after) {
	}

	/** Mirrors the journal kinds of {@code itsm_incident_change}. */
	enum ChangeKind {
		ADDED,
		UPDATED,
		MISSING,
		RETURNED
	}
}
