package ru.hardcode.dashboard.service;

import java.util.Set;

/**
 * Committed source changes. Consumers can refresh the affected cards and use the journal under
 * this revision to find which groups were affected (including groups an incident left).
 */
public record ItsmSnapshotPublished(String sourceId, long sourceVersion, long changeRevision,
                                    Set<String> affectedIncidentIds) {
	public ItsmSnapshotPublished {
		affectedIncidentIds = Set.copyOf(affectedIncidentIds);
	}
}
