package ru.hardcode.dashboard.dto;

import java.util.Map;

public record IncidentPresetResponse(long id, String name, String queue, Map<String, String> filters) {
}
