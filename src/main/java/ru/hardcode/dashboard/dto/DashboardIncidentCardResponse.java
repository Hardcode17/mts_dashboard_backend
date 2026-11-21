package ru.hardcode.dashboard.dto;

import java.util.List;
import java.util.Map;

public record DashboardIncidentCardResponse(
        String id, String header, String description, String productName, String productMasterId,
        String service, String serviceType, String assignedGroupName, String assignedEmployeeLogin,
        String assignedEmployee, String stateEnum, String stateReason, int priorityId, String submitDate,
        String lastAssignmentToTeamDateTime, String deadline, String completionDate, String solution,
        List<Map<String,String>> groupMemberships, Long remainingSeconds, boolean overdue, String itsmUrl,
        boolean itsmLinkDemo, long revision, long eventSeq) {}
