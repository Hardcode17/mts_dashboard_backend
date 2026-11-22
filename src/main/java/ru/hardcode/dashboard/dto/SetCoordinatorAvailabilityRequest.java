package ru.hardcode.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import tools.jackson.databind.JsonNode;

public record SetCoordinatorAvailabilityRequest(boolean available) {

	@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
	public static SetCoordinatorAvailabilityRequest from(JsonNode body) {
		var available = body == null ? null : body.get("available");
		if (body == null || !body.isObject() || body.size() != 1 || available == null || !available.isBoolean()) {
			throw new IllegalArgumentException("The availability request accepts only a boolean available field");
		}
		return new SetCoordinatorAvailabilityRequest(available.booleanValue());
	}
}
