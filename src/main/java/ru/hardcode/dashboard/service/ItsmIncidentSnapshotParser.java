package ru.hardcode.dashboard.service;

import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import ru.hardcode.dashboard.dto.ItsmIncidentRecord;
import ru.hardcode.dashboard.dto.ItsmIncidentSnapshot;
import ru.hardcode.dashboard.entity.ItsmIncidentState;
import ru.hardcode.dashboard.exception.AppException;
import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.ItsmRecordValidationException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

/**
 * Decodes a full ITSM snapshot into normalized records. An invalid record or duplicate ID
 * rejects the entire snapshot. Pass-through fields are retained for downstream consumers.
 */
@Component
public class ItsmIncidentSnapshotParser {

	static final String FIELD_DEADLINE = "deadline";
	static final String FIELD_WAITING_EXIT_DATE = "waitingExitDate";
	static final String FIELD_PARENT_ID = "parentId";
	private static final Instant TECHNICAL_DATE = Instant.parse("0001-01-01T00:00:00Z");
	static final String FIELD_COMPLETION_DATE = "completionDate";
	static final String FIELD_SOLUTION = "solution";

	private static final String REASON_MISSING = "is missing";
	private static final String REASON_NOT_OBJECT = "must be a JSON object";
	private static final List<ItsmIncidentState> KNOWN_STATES = List.of(ItsmIncidentState.values());

	private final ObjectReader reader;

	public ItsmIncidentSnapshotParser(ObjectMapper objectMapper) {
		this.reader = objectMapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
	}

	/**
	 * Parses exactly one JSON array and every record in it.
	 *
	 * @throws AppException {@link AppErrors#ITSM_INVALID_PAYLOAD} for malformed JSON or a
	 *         non-array top level, {@link AppErrors#ITSM_INVALID_RECORD} for an invalid field,
	 *         and {@link AppErrors#ITSM_DUPLICATE_RECORD} for a repeated ID
	 */
	public ItsmIncidentSnapshot parse(String body) {
		if (body == null || body.isBlank()) {
			throw new AppException(AppErrors.ITSM_INVALID_PAYLOAD);
		}
		try {
			return parseTree(reader.readTree(body));
		} catch (JacksonException exception) {
			throw new AppException(AppErrors.ITSM_INVALID_PAYLOAD, exception);
		}
	}

	/**
	 * @see #parse(String)
	 */
	public ItsmIncidentSnapshot parse(byte[] body) {
		if (body == null || body.length == 0) {
			throw new AppException(AppErrors.ITSM_INVALID_PAYLOAD);
		}
		try {
			return parseTree(reader.readTree(body));
		} catch (JacksonException exception) {
			throw new AppException(AppErrors.ITSM_INVALID_PAYLOAD, exception);
		}
	}

	private ItsmIncidentSnapshot parseTree(JsonNode root) {
		if (root == null || !root.isArray()) {
			throw new AppException(AppErrors.ITSM_INVALID_PAYLOAD);
		}
		var records = new ArrayList<ItsmIncidentRecord>(root.size());
		var firstIndexById = new HashMap<String, Integer>(root.size());
		for (int index = 0; index < root.size(); index++) {
			ItsmIncidentRecord record = parseRecord(index, root.get(index));
			Integer firstIndex = firstIndexById.putIfAbsent(record.id(), index);
			if (firstIndex != null) {
				throw new AppException(AppErrors.ITSM_DUPLICATE_RECORD, Map.of("index", index, "firstIndex", firstIndex));
			}
			records.add(record);
		}
		return new ItsmIncidentSnapshot(records);
	}

	private ItsmIncidentRecord parseRecord(int index, JsonNode node) {
		if (node == null || !node.isObject()) {
			throw invalid(index, null, REASON_NOT_OBJECT, null);
		}
		String id = requiredText(index, node, "id");
		if (id == null || id.isBlank()) {
			throw invalid(index, "id", "must be a non-blank string", null);
		}
		validateParentId(index, node);
		ItsmIncidentState state = parseState(index, node);
		int priorityId = requiredIntegralInt(index, node, "priorityId");
		Instant submitDate = requiredTimestamp(index, node, "submitDate");
		Instant assignedAt = optionalTimestamp(index, node, "lastAssignmentToTeamDateTime");
		Instant deadline = optionalTimestamp(index, node, FIELD_DEADLINE);
		Instant waitingExitDate = optionalTimestamp(index, node, FIELD_WAITING_EXIT_DATE);
		Instant completionDate = optionalTimestamp(index, node, FIELD_COMPLETION_DATE);
		String solution = optionalSolution(index, node);
		if (!state.isCompleted()) {
			solution = null;
		}

		if (state.isCompleted()) {
			if (completionDate == null) {
				throw invalid(index, FIELD_COMPLETION_DATE, "is required for completed state " + state.name(), null);
			}
			if (solution == null) {
				throw invalid(index, FIELD_SOLUTION, "is required for completed state " + state.name(), null);
			}
		} else if (completionDate != null) {
			throw invalid(index, FIELD_COMPLETION_DATE, "must be null for active state " + state.name(), null);
		}

		var raw = new LinkedHashMap<String, Object>(node.size());
		node.properties().forEach(entry -> raw.put(entry.getKey(), normalizeTree(entry.getValue())));
		raw.put("id", id);
		raw.put("stateEnum", state.name());
		raw.put("priorityId", priorityId);
		raw.put("submitDate", submitDate.toString());
		raw.put("lastAssignmentToTeamDateTime", timestampString(assignedAt));
		raw.put(FIELD_DEADLINE, timestampString(deadline));
		raw.put(FIELD_WAITING_EXIT_DATE, timestampString(waitingExitDate));
		raw.put(FIELD_COMPLETION_DATE, timestampString(completionDate));
		raw.put(FIELD_SOLUTION, solution);

		return new ItsmIncidentRecord(
				id, state, priorityId, submitDate, assignedAt, deadline, completionDate, solution, waitingExitDate, raw);
	}

	private static ItsmIncidentState parseState(int index, JsonNode node) {
		String value = requiredText(index, node, "stateEnum");
		for (ItsmIncidentState state : KNOWN_STATES) {
			if (state.name().equals(value)) {
				return state;
			}
		}
		throw invalid(index, "stateEnum", "must be one of Assigned, WorkInProgress, Resolved, Closed, Research", null);
	}

	/**
	 * The parent is optional: an absent field preserves the previously imported relation and an
	 * explicit null removes it. A present value must be the external incident id of the parent.
	 */
	private static void validateParentId(int index, JsonNode node) {
		JsonNode value = node.get(FIELD_PARENT_ID);
		if (value == null || value.isNull()) {
			return;
		}
		if (!value.isString()) {
			throw invalid(index, FIELD_PARENT_ID, "must be a string or null", null);
		}
		if (value.stringValue().isBlank()) {
			throw invalid(index, FIELD_PARENT_ID, "must be a non-blank string", null);
		}
	}

	private static String timestampString(Instant instant) {
		return instant == null ? null : instant.toString();
	}

	/** Blank solution text is equivalent to null. */
	private static String optionalSolution(int index, JsonNode node) {
		String value = requiredText(index, node, FIELD_SOLUTION);
		return value == null || value.isBlank() ? null : value;
	}

	private static String requiredText(int index, JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null) {
			throw invalid(index, field, REASON_MISSING, null);
		}
		if (value.isNull()) {
			return null;
		}
		if (!value.isString()) {
			throw invalid(index, field, "must be a string", null);
		}
		return value.stringValue();
	}

	/**
	 * Accepts only a JSON integral literal. Jackson parses integer literals beyond {@code long}
	 * as exact big integers, so both the range and the "integral" decision are exact.
	 */
	private static int requiredIntegralInt(int index, JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null) {
			throw invalid(index, field, REASON_MISSING, null);
		}
		if (!value.isIntegralNumber()) {
			throw invalid(index, field, "must be an integral number", null);
		}
		if (!value.canConvertToInt()) {
			throw invalid(index, field, "is out of 32-bit integer range", null);
		}
		return value.intValue();
	}

	private static Instant requiredTimestamp(int index, JsonNode node, String field) {
		Instant value = optionalTimestamp(index, node, field);
		if (value == null) {
			throw invalid(index, field, "must be a UTC timestamp string", null);
		}
		return value;
	}

	private static Instant optionalTimestamp(int index, JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || value.isNull()) {
			return null;
		}
		if (!value.isString()) {
			throw invalid(index, field, "must be a UTC timestamp string", null);
		}
		Instant timestamp = parseUtc(value.stringValue(), index, field);
		return TECHNICAL_DATE.equals(timestamp) ? null : timestamp;
	}

	private static Instant parseUtc(String value, int index, String field) {
		try {
			LocalDateTime dateTime;
			ZoneOffset offset;
			try {
				OffsetDateTime parsed = OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
				dateTime = parsed.toLocalDateTime();
				offset = parsed.getOffset();
			} catch (DateTimeException exception) {
				dateTime = LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
				offset = ZoneOffset.UTC;
			}
			if (!ZoneOffset.UTC.equals(offset)) {
				throw invalid(index, field, "must be UTC (offset Z or +00:00)", null);
			}
			if (dateTime.getYear() < 1 || dateTime.getYear() > 9999) {
				throw invalid(index, field, "must have a year between 0001 and 9999", null);
			}
			if (dateTime.getNano() % 1_000 != 0) {
				throw invalid(index, field, "must not have sub-microsecond precision", null);
			}
			return dateTime.toInstant(offset).truncatedTo(ChronoUnit.MICROS);
		} catch (DateTimeException | ArithmeticException exception) {
			throw invalid(index, field, "must be an ISO-8601 timestamp", exception);
		}
	}

	private static Object normalizeTree(JsonNode node) {
		if (node.isObject()) {
			var object = new LinkedHashMap<String, Object>(node.size());
			node.properties().forEach(entry -> object.put(entry.getKey(), normalizeTree(entry.getValue())));
			return Collections.unmodifiableMap(object);
		}
		if (node.isArray()) {
			var array = new ArrayList<Object>(node.size());
			node.values().forEach(element -> array.add(normalizeTree(element)));
			return Collections.unmodifiableList(array);
		}
		return normalizeScalar(node);
	}

	/**
	 * Raw values are preserved exactly as typed, so callers can inspect the full original
	 * record. {@code short}, {@code byte}, and {@code float} are promoted because the
	 * PostgreSQL precision is not representable in those Java types.
	 */
	private static Object normalizeScalar(JsonNode node) {
		if (node.isNull()) {
			return null;
		}
		if (node.isBoolean()) {
			return node.booleanValue();
		}
		if (node.isIntegralNumber()) {
			BigInteger value = node.bigIntegerValue();
			return value.bitLength() < Integer.SIZE ? (Object) value.intValue()
					: value.bitLength() < Long.SIZE ? (Object) value.longValue()
					: value;
		}
		if (node.isNumber()) {
			return node.decimalValue();
		}
		return node.stringValue();
	}

	private static ItsmRecordValidationException invalid(int index, String field, String reason, Throwable cause) {
		return new ItsmRecordValidationException(index, field == null ? "record" : field, reason, cause);
	}
}
