package ru.hardcode.dashboard.dto;

import java.time.Instant;
import java.util.List;

import ru.hardcode.dashboard.entity.ItsmIncidentState;

public record DashboardIncidentListResponse(
		long eventSeq, long revision, boolean beforeFirstSuccess, List<Group> groups, List<Summary> items,
		String nextCursor
) {
	public record Group(String key, String title, String rule, boolean active, int incidentCount,
			List<Summary> incidents, Long fixed) {
	}

	public record Summary(String id, String productName, String assignedGroupName, ItsmIncidentState stateEnum,
			int priorityId, Instant submitDate, Instant deadline, Instant completionDate, List<Membership> groupMemberships) {
	}

	public record Membership(String group, String matchedRule) {
	}
}
