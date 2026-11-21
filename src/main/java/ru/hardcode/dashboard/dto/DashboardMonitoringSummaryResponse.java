package ru.hardcode.dashboard.dto;

import java.time.Instant;
import java.util.List;

/**
 * KAN-62 monitoring read model. Categories overlap, so their counts are independent and are
 * not expected to add up to {@code activeCount}. The timeline contains only durable
 * observations from an actual publication within the last 24 hours, oldest first.
 */
public record DashboardMonitoringSummaryResponse(
		Instant updatedAt,
		int activeCount,
		Counts counts,
		List<TimelinePoint> timeline,
		List<PriorityCount> priorities,
		List<CoordinatorLoad> coordinatorLoad
) {
	public record Counts(int critical, int high, int coordinators, int research) {
	}

	public record TimelinePoint(Instant at, int critical, int high, int coordinators, int research) {
	}

	public record PriorityCount(int priorityId, int count) {
	}

	public record CoordinatorLoad(String name, int count) {
	}
}
