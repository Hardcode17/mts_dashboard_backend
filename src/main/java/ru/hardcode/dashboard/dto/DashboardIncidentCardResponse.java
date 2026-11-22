package ru.hardcode.dashboard.dto;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import ru.hardcode.dashboard.dto.DashboardIncidentListResponse.Membership;
import ru.hardcode.dashboard.entity.ItsmIncidentState;

public record DashboardIncidentCardResponse(
		String id, String header, String description, String productName, String productMasterId,
		String service, String serviceType, String assignedGroupName, String assignedEmployeeLogin,
		String assignedEmployee, ItsmIncidentState stateEnum, String stateReason, int priorityId, Instant submitDate,
		Instant lastAssignmentToTeamDateTime, Instant deadline, Instant completionDate, String solution,
		Long remainingSeconds, boolean overdue, URI itsmUrl, boolean itsmDemo, String meetingUrl,
		List<Membership> groupMemberships, DeadlineRiskResponse deadlineRisk,
		List<ConfirmedIncidentLinkResponse> confirmedLinks, long eventSeq, long revision
) {
}
