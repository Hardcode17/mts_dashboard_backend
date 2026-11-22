package ru.hardcode.dashboard.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CreateShiftHandoverRequest(UUID requestId, Long recipientId, List<Long> taskIds, String summary,
		Map<Long, String> situationKeys) {
}
