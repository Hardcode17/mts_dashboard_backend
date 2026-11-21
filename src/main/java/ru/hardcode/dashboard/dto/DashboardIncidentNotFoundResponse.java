package ru.hardcode.dashboard.dto;

public record DashboardIncidentNotFoundResponse(String code, String message, long eventSeq, long revision) {
}
