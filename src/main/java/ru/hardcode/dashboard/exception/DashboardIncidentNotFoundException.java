package ru.hardcode.dashboard.exception;

import java.util.Map;

import ru.hardcode.dashboard.dto.DashboardIncidentNotFoundResponse;

public class DashboardIncidentNotFoundException extends AppException {

	private final long eventSeq;
	private final long revision;

	public DashboardIncidentNotFoundException(String incidentId, long eventSeq, long revision) {
		super(AppErrors.INCIDENT_NOT_FOUND, Map.of("incident", eventSeq == 0 ? "" : " " + incidentId));
		this.eventSeq = eventSeq;
		this.revision = revision;
	}

	DashboardIncidentNotFoundResponse toResponse() {
		return new DashboardIncidentNotFoundResponse(getAppError().getCode(), getMessage(), eventSeq, revision);
	}
}
