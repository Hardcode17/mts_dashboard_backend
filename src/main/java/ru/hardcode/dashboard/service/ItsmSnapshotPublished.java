package ru.hardcode.dashboard.service;

import java.util.Set;

/**
 * Notification of committed source changes. Listeners should replay the journal via
 * {@link ItsmSnapshotPublicationService#changesAfter(long, int)} after their revision cursor;
 * notifications can be missed or arrive out of order. The journal's before/after data identifies
 * the affected groups, including groups an incident left.
 */
public record ItsmSnapshotPublished(String sourceId, long sourceVersion, long changeRevision,
                                    Set<String> affectedIncidentIds) {
	public ItsmSnapshotPublished {
		affectedIncidentIds = Set.copyOf(affectedIncidentIds);
	}
}
