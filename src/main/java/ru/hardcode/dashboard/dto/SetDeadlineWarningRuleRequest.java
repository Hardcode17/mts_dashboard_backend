package ru.hardcode.dashboard.dto;

import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.AppException;
import tools.jackson.databind.JsonNode;

public record SetDeadlineWarningRuleRequest(int thresholdSeconds) {

	public static SetDeadlineWarningRuleRequest from(JsonNode body) {
		var value = body.path("thresholdSeconds");
		if (body.size() != 1 || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1) {
			throw new AppException(AppErrors.INVALID_DEADLINE_WARNING_RULE);
		}
		return new SetDeadlineWarningRuleRequest(value.intValue());
	}
}
