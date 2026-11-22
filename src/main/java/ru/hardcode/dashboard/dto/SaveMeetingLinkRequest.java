package ru.hardcode.dashboard.dto;

import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.AppException;
import tools.jackson.databind.JsonNode;

public record SaveMeetingLinkRequest(String meetingUrl) {

	public static SaveMeetingLinkRequest from(JsonNode body) {
		var url = body.path("meetingUrl");
		if (!body.isObject() || body.size() != 1 || !url.isString()) {
			throw new AppException(AppErrors.INVALID_MEETING_LINK);
		}
		return new SaveMeetingLinkRequest(url.stringValue());
	}
}
