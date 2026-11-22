package ru.hardcode.dashboard.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import ru.hardcode.dashboard.dto.DashboardIncidentListResponse.Summary;
import ru.hardcode.dashboard.entity.ItsmIncidentState;
import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.AppException;

/**
 * Filters of the published incident list (KAN-26). The query names match the coordinator UI:
 * {@code q}, {@code priority}, {@code product}, {@code team}, {@code state}, {@code serviceType},
 * {@code deadline} and {@code sort}. Empty values mean "no filter".
 */
public record IncidentListFilter(
		String query,
		Integer priority,
		String product,
		String team,
		ItsmIncidentState state,
		String serviceType,
		DeadlineState deadline,
		Sort sort
) {

	public enum DeadlineState {
		NONE,
		LATE,
		NEAR
	}

	public enum Sort {
		UPDATED,
		REGISTERED,
		DEADLINE
	}

	public static IncidentListFilter from(
			String query,
			String priority,
			String product,
			String team,
			String state,
			String serviceType,
			String deadline,
			String sort
	) {
		return new IncidentListFilter(
				text(query),
				priority(priority),
				text(product),
				text(team),
				state(state),
				text(serviceType),
				deadline(deadline),
				sort(sort)
		);
	}

	private static final IncidentListFilter DEFAULT =
			new IncidentListFilter(null, null, null, null, null, null, null, Sort.UPDATED);

	static IncidentListFilter defaults() {
		return DEFAULT;
	}

	String key(List<Summary> selected) {
		if (equals(DEFAULT)) {
			return "";
		}
		MessageDigest digest = newDigest();
		update(digest, query == null ? "" : query.toLowerCase(Locale.ROOT));
		update(digest, priority);
		update(digest, product);
		update(digest, team);
		update(digest, state);
		update(digest, serviceType);
		update(digest, deadline);
		update(digest, sort);
		if (deadline == DeadlineState.LATE || deadline == DeadlineState.NEAR) {
			selected.forEach(item -> update(digest, item.id()));
		}
		return "-" + HexFormat.of().formatHex(digest.digest(), 0, 12);
	}

	private static void update(MessageDigest digest, Object value) {
		byte[] bytes = Objects.toString(value, "").getBytes(StandardCharsets.UTF_8);
		digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
		digest.update((byte) ':');
		digest.update(bytes);
	}

	private static MessageDigest newDigest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static String text(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private static Integer priority(String value) {
		String text = text(value);
		if (text == null) {
			return null;
		}
		try {
			int parsed = Integer.parseInt(text);
			if (parsed < 1 || parsed > 4) {
				throw invalid("priority", text);
			}
			return parsed;
		} catch (NumberFormatException exception) {
			throw invalid("priority", text);
		}
	}

	private static ItsmIncidentState state(String value) {
		String text = text(value);
		if (text == null) {
			return null;
		}
		try {
			return ItsmIncidentState.valueOf(text);
		} catch (IllegalArgumentException exception) {
			throw invalid("state", text);
		}
	}

	private static DeadlineState deadline(String value) {
		String text = text(value);
		if (text == null) {
			return null;
		}
		return switch (text) {
			case "none" -> DeadlineState.NONE;
			case "late" -> DeadlineState.LATE;
			case "near" -> DeadlineState.NEAR;
			default -> throw invalid("deadline", text);
		};
	}

	private static Sort sort(String value) {
		String text = text(value);
		if (text == null) {
			return Sort.UPDATED;
		}
		return switch (text) {
			case "updated" -> Sort.UPDATED;
			case "registered" -> Sort.REGISTERED;
			case "deadline" -> Sort.DEADLINE;
			default -> throw invalid("sort", text);
		};
	}

	private static AppException invalid(String field, String value) {
		return new AppException(AppErrors.INVALID_INCIDENT_FILTER, java.util.Map.of(
				"field", field, "value", value));
	}
}
