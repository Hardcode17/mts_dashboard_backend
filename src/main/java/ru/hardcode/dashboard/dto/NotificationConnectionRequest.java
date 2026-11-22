package ru.hardcode.dashboard.dto;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.AppException;
import tools.jackson.databind.JsonNode;

public record NotificationConnectionRequest(
		String channel, String address, boolean active, List<Integer> priorityIds,
		boolean secretRefProvided, String secretRef
) {

	private static final Set<String> FIELDS = Set.of("channel", "address", "active", "priorityIds", "secretRef");
	private static final Pattern ADDRESS = Pattern.compile("[a-zA-Z0-9@+_.-]{1,254}");
	private static final Pattern SECRET_REF = Pattern.compile("[A-Z][A-Z0-9_]{0,99}");

	public static NotificationConnectionRequest from(JsonNode body) {
		if (!body.isObject() || body.properties().stream().anyMatch(entry -> !FIELDS.contains(entry.getKey()))) {
			throw invalid();
		}
		var channel = body.path("channel");
		var address = body.path("address");
		var active = body.path("active");
		var secretRef = body.path("secretRef");
		if (!channel.isString() || !address.isString() || !ADDRESS.matcher(address.stringValue()).matches()
				|| !active.isBoolean()) {
			throw invalid();
		}
		if (!secretRef.isMissingNode() && !secretRef.isNull()
				&& (!secretRef.isString() || !SECRET_REF.matcher(secretRef.stringValue()).matches())) {
			throw invalid();
		}
		return new NotificationConnectionRequest(channel.stringValue(), address.stringValue(), active.booleanValue(),
				priorities(body.path("priorityIds")), !secretRef.isMissingNode(),
				secretRef.isString() ? secretRef.stringValue() : null);
	}

	private static List<Integer> priorities(JsonNode values) {
		if (!values.isArray() || values.size() > 4) {
			throw invalid();
		}
		var ids = new TreeSet<Integer>();
		for (JsonNode value : values) {
			if (!value.isIntegralNumber() || !value.canConvertToInt()
					|| value.intValue() < 1 || value.intValue() > 4 || !ids.add(value.intValue())) {
				throw invalid();
			}
		}
		return List.copyOf(ids);
	}

	private static AppException invalid() {
		return new AppException(AppErrors.INVALID_NOTIFICATION_CONNECTION);
	}
}
