package ru.hardcode.dashboard.dto;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.AppException;
import tools.jackson.databind.JsonNode;

public record IncidentPresetRequest(String name, String queue, Map<String, String> filters) {

	private static final Set<String> QUEUES = Set.of("all", "critical", "coordinator", "high", "research");
	private static final Set<String> TEXT_FILTERS = Set.of("q", "product", "team", "serviceType");
	private static final Map<String, Set<String>> CHOICES = Map.of(
			"priority", Set.of("1", "2", "3", "4"),
			"state", Set.of("Assigned", "WorkInProgress", "Resolved", "Closed", "Research"),
			"deadline", Set.of("none", "late", "near"),
			"sort", Set.of("updated", "registered", "deadline"),
			"size", Set.of("5", "25", "50", "100"),
			"sla", Set.of("soon")
	);

	public static IncidentPresetRequest from(JsonNode body) {
		if (body == null || !body.isObject() || body.size() != 3 || !body.path("filters").isObject()) {
			throw invalid();
		}
		String name = text(body.path("name"), 80);
		String queue = text(body.path("queue"), 16);
		if (!QUEUES.contains(queue)) {
			throw invalid();
		}
		Map<String, String> filters = new LinkedHashMap<>();
		for (var entry : body.path("filters").properties()) {
			String key = entry.getKey();
			String value = text(entry.getValue(), 300);
			if (!TEXT_FILTERS.contains(key) && !CHOICES.getOrDefault(key, Set.of()).contains(value)) {
				throw invalid();
			}
			if ("sla".equals(key) && !"critical".equals(queue)) {
				throw invalid();
			}
			filters.put(key, value);
		}
		return new IncidentPresetRequest(name, queue, Map.copyOf(filters));
	}

	private static String text(JsonNode node, int maxLength) {
		if (!node.isString()) {
			throw invalid();
		}
		String value = node.stringValue().strip();
		if (value.isEmpty() || value.length() > maxLength || value.indexOf(0) >= 0) {
			throw invalid();
		}
		return value;
	}

	private static AppException invalid() {
		return new AppException(AppErrors.INVALID_INCIDENT_PRESET);
	}
}
