package ru.hardcode.dashboard.dto;

import java.time.Instant;
import java.util.List;

import ru.hardcode.dashboard.entity.ItsmIncidentState;

/**
 * Read-only suggestions of similar published incidents for one INC (KAN-42). Every candidate
 * explains the proposed match: the score, human-readable reasons and the shared attributes the
 * scoring used. The ITSM {@code isDouble}/{@code isMaster} flags of a candidate are context only
 * and never create a suggestion; confirming a relation is a separate coordinator action.
 */
public record DashboardSimilarIncidentsResponse(
		String incidentId, long eventSeq, long revision, List<Candidate> candidates
) {
	public record Candidate(
			String incidentId, int score, List<String> reasons, SharedAttributes sharedAttributes,
			String header, ItsmIncidentState stateEnum, int priorityId, Instant submitDate,
			long submitDifferenceSeconds, Boolean isDouble, Boolean isMaster
	) {
	}

	/** Attributes shared by the target and the candidate; a signal the scoring did not use is null. */
	public record SharedAttributes(
			String productName, String service, String scenarioName, String errorCode
	) {
	}
}
