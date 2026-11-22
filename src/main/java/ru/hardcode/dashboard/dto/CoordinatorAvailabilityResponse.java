package ru.hardcode.dashboard.dto;

import java.time.Instant;
import java.util.List;

public record CoordinatorAvailabilityResponse(
		boolean available, Instant availableSince, long generation, List<Group> groups
) {
	public record Group(long id, String name) {
	}
}
