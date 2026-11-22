package ru.hardcode.dashboard.dto;

import java.util.List;

public record NotificationConnectionResponse(long id, String channel, String address, boolean active,
		List<Integer> priorityIds) {
}
