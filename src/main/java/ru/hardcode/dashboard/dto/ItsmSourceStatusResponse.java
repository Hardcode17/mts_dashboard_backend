package ru.hardcode.dashboard.dto;

public record ItsmSourceStatusResponse(
		String sourceId,
		Long observedVersion,
		Long changeRevision,
		Long waitingForVersion
) {
}
