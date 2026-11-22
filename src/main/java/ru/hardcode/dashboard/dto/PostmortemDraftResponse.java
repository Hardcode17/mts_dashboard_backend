package ru.hardcode.dashboard.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PostmortemDraftResponse(
		String incidentId, long resolutionEventSeq, Instant observedAt, String resolutionText, String rootCause,
		List<TimelineFact> timeline, List<ShiftNote> notes, List<EvidenceFact> evidence, List<String> gaps) {

	public record TimelineFact(long eventSeq, Instant observedAt, String changeKind, String beforeState,
			String afterState, String href) {
	}

	public record ShiftNote(String noteId, long eventSeq, Instant observedAt, String text, String href) {
	}

	public record EvidenceFact(UUID runId, String evidenceId, String source, String serviceKey, Instant observedAt,
			String message, Long value, Long recordCount, String serviceState, String href) {
	}
}
