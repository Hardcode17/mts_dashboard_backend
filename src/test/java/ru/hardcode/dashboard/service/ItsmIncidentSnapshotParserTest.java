package ru.hardcode.dashboard.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.hardcode.dashboard.dto.ItsmIncidentRecord;
import ru.hardcode.dashboard.dto.ItsmIncidentSnapshot;
import ru.hardcode.dashboard.entity.ItsmIncidentState;
import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.AppException;
import ru.hardcode.dashboard.exception.ItsmRecordValidationException;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused KAN-21 check: the original ITSM REST array shape parses into the full typed,
 * normalized, immutable result, and every rejected value surfaces as a safe field-level
 * exception.
 */
class ItsmIncidentSnapshotParserTest {

	private static final String ACTIVE_ID = "INC000700000000";
	private static final String RESEARCH_ID = "INC000700000004";
	private static final String SUBMIT_DATE = "2026-11-21T09:55:00Z";

	private final ObjectMapper objectMapper = new ObjectMapper();

	private ItsmIncidentSnapshotParser parser;

	@BeforeEach
	void setUp() {
		parser = new ItsmIncidentSnapshotParser(objectMapper);
	}

	@Test
	void fixtureParsesIntoFullTypedNormalizedImmutableResult() {
		ItsmIncidentSnapshot snapshot = parser.parse(fixture());

		assertThat(snapshot.size()).isEqualTo(5);
		assertThat(snapshot.ids()).containsExactly(
				ACTIVE_ID, "INC000700000001", "INC000700000002", "INC000700000003", RESEARCH_ID);
		assertThat(snapshot.records()).extracting(ItsmIncidentRecord::state)
				.containsExactly(ItsmIncidentState.values());

		ItsmIncidentRecord active = snapshot.find(ACTIVE_ID).orElseThrow();
		assertThat(active.id()).isEqualTo(ACTIVE_ID);
		assertThat(active.state()).isEqualTo(ItsmIncidentState.Assigned);
		assertThat(active.isActive()).isTrue();
		assertThat(active.priorityId()).isEqualTo(1);
		assertThat(active.submitDate()).isEqualTo(Instant.parse(SUBMIT_DATE));
		assertThat(active.lastAssignmentToTeamDateTime()).isEqualTo(Instant.parse(SUBMIT_DATE));
		assertThat(active.completionDate()).isNull();
		assertThat(active.solution()).isNull();
		assertThat(active.technicalReferenceDate()).isNull();

		// The full original record survives normalization, including its null values and order.
		Map<String, Object> raw = active.raw();
		assertThat(raw)
				.containsEntry("sysId", "000700000000")
				.containsEntry("header", "Оплата · Вход")
				.containsEntry("assignedEmployee", "Дежурный 1 (demo0)")
				.containsEntry("isEffect", false)
				.containsEntry("productId", "7000")
				.containsEntry("serviceTypeId", 30)
				.containsEntry("waitingExitDate", null)
				.containsEntry("deadline", null)
				.containsEntry("completionDate", null)
				.containsEntry("solution", null)
				.containsEntry("stateEnum", "Assigned")
				.containsEntry("priorityId", 1)
				.containsEntry("submitDate", SUBMIT_DATE);
		assertThat(List.copyOf(raw.keySet())).startsWith("sysId", "id", "stateReason")
				.containsSubsequence("deadline", "lastAssignmentToTeamDateTime");
		assertThatThrownBy(() -> active.raw().put("id", "other")).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> snapshot.records().clear()).isInstanceOf(UnsupportedOperationException.class);

		ItsmIncidentRecord researching = snapshot.find(RESEARCH_ID).orElseThrow();
		assertThat(researching.state()).isEqualTo(ItsmIncidentState.Research);
		assertThat(researching.isActive()).isFalse();
		assertThat(researching.priorityId()).isEqualTo(1);
		assertThat(researching.solution()).isEqualTo("Восстановление подтверждено на стенде.");
		assertThat(researching.completionDate()).isEqualTo(Instant.parse("2026-11-21T09:56:00Z"));
		assertThat(researching.technicalReferenceDate()).isNull();
	}

	@Test
	void emptyArrayIsAValidEmptySnapshot() {
		assertThat(parser.parse("[]").isEmpty()).isTrue();
		assertThat(parser.parse("[]".getBytes(StandardCharsets.UTF_8))).isEqualTo(new ItsmIncidentSnapshot(List.of()));
	}

	@Test
	void byteInputReadsTheSameOriginalArray() {
		assertThat(parser.parse(fixture().getBytes(StandardCharsets.UTF_8))).isEqualTo(parser.parse(fixture()));
	}

	@Test
	void zeroOffsetTimestampsAreKeptAsTheSameInstantWithMicroseconds() {
		String body = write(List.of(
				named(incident(0), Map.of(
						"submitDate", "2026-11-21T09:55:00.123456+00:00",
						"lastAssignmentToTeamDateTime", "2026-11-21T10:00:00.5Z"))));

		ItsmIncidentRecord record = parser.parse(body).records().getFirst();

		assertThat(record.submitDate()).isEqualTo(Instant.parse("2026-11-21T09:55:00.123456Z"));
		assertThat(record.lastAssignmentToTeamDateTime()).isEqualTo(Instant.parse("2026-11-21T10:00:00.500000Z"));
	}

	@Test
	void offsetLessTimestampIsReadAsUtc() {
		String body = write(List.of(named(incident(0), Map.of("submitDate", "2026-11-21T09:55:00"))));

		assertThat(parser.parse(body).records().getFirst().submitDate()).isEqualTo(Instant.parse(SUBMIT_DATE));
	}

	// --- record identity --------------------------------------------------------------------

	@Test
	void blankIdIsRejected() {
		assertRecordError(single(record -> record.put("id", "   ")), 0, "id",
				"ITSM record in snapshot at index 0 has invalid field id: must be a non-blank string");
	}

	@Test
	void nullOrNonStringIdIsRejected() {
		assertRecordError(single(record -> record.put("id", null)), 0, "id");
		assertRecordError(single(record -> record.put("id", 700_000_000L)), 0, "id");
	}

	@Test
	void missingIdIsRejected() {
		assertRecordError(single(record -> record.remove("id")), 0, "id");
	}

	@Test
	void duplicateIdAcrossSnapshotIsRejected() {
		Map<String, Object> first = incident(0);
		Map<String, Object> second = incident(1);
		second.put("id", first.get("id"));

		assertThatThrownBy(() -> parser.parse(write(List.of(first, second))))
				.isInstanceOfSatisfying(AppException.class, exception -> {
					assertThat(exception.getAppError()).isEqualTo(AppErrors.ITSM_DUPLICATE_RECORD);
					assertThat(exception.getMessage())
							.isEqualTo("ITSM snapshot contains duplicate id; record at index 1 repeats index 0");
				});
	}

	// --- stateEnum --------------------------------------------------------------------------

	@Test
	void unknownStateEnumIsRejected() {
		assertRecordError(single(record -> record.put("stateEnum", "Pending")), 0, "stateEnum",
				"ITSM record in snapshot at index 0 has invalid field stateEnum: "
						+ "must be one of Assigned, WorkInProgress, Resolved, Closed, Research");
	}

	@Test
	void stateEnumMustMatchExactly() {
		assertRecordError(single(record -> record.put("stateEnum", "assigned")), 0, "stateEnum");
	}

	@Test
	void stateEnumMustBeAString() {
		assertRecordError(single(record -> record.put("stateEnum", 0)), 0, "stateEnum");
	}

	// --- priorityId -------------------------------------------------------------------------

	@Test
	void integralPriorityIdMayBeNegativeOrZero() {
		String body = write(List.of(
				named(incident(0), Map.of("priorityId", 0)),
				named(incident(1), Map.of("priorityId", -3))));

		assertThat(parser.parse(body).records()).extracting(ItsmIncidentRecord::priorityId).containsExactly(0, -3);
	}

	@Test
	void nonIntegralPriorityIdIsRejected() {
		assertRecordError(single(record -> record.put("priorityId", 1.5)), 0, "priorityId",
				"ITSM record in snapshot at index 0 has invalid field priorityId: must be an integral number");
	}

	@Test
	void decimalLiteralIsNotIntegralPriorityId() {
		// A record built as plain Java must still be rejected once typed: 2.0 is not an integral JSON number.
		assertRecordError(single(record -> record.put("priorityId", new BigDecimal("2.0"))), 0, "priorityId");
	}

	@Test
	void priorityEnumNameIsNotAcceptedAsPriorityId() {
		assertRecordError(single(record -> record.put("priorityId", "Низкий")), 0, "priorityId");
	}

	@Test
	void textualPriorityIsPreservedAlongsideNumericPriority() {
		ItsmIncidentRecord incident = parser.parse(single(record -> record.put("priority", "Низкий")))
				.records().getFirst();

		assertThat(incident.priorityId()).isEqualTo(1);
		assertThat(incident.raw()).containsEntry("priority", "Низкий");
	}

	@Test
	void outOfRangePriorityIdIsRejected() {
		assertRecordError(single(record -> record.put("priorityId", 2_147_483_648L)), 0, "priorityId");
		assertRecordError(single(record -> record.put("priorityId", new BigInteger("99999999999999999999999"))), 0,
				"priorityId");
	}

	@Test
	void missingPriorityIdIsRejected() {
		assertRecordError(single(record -> record.remove("priorityId")), 0, "priorityId");
	}

	// --- null fields ------------------------------------------------------------------------

	@Test
	void deadlineMayBePresentOrNull() {
		String deadline = "2026-11-21T11:25:00.123456Z";
		ItsmIncidentRecord scheduled = parser.parse(single(record -> record.put("deadline", deadline)))
				.records().getFirst();
		assertThat(scheduled.deadline()).isEqualTo(Instant.parse(deadline));
		assertThat(scheduled.raw()).containsEntry("deadline", deadline);
		assertThat(parser.parse(fixture()).records().getFirst().deadline()).isNull();
		assertRecordError(single(record -> record.put("deadline", 42)), 0, "deadline");
	}

	@Test
	void waitingExitDateIsNullForPlaceholderAndPreservedForRealTime() {
		String actual = "2026-11-21T10:15:00Z";
		ItsmIncidentRecord record = parser.parse(single(incident -> incident.put("waitingExitDate", actual)))
				.records().getFirst();
		assertThat(record.technicalReferenceDate()).isEqualTo(Instant.parse(actual));
		assertThat(record.raw()).containsEntry("waitingExitDate", actual);
		assertThat(parser.parse(single(incident -> incident.put("waitingExitDate", "0001-01-01T00:00:00Z")))
				.records().getFirst().technicalReferenceDate()).isNull();
	}

	@Test
	void solutionOfActiveIncidentIsCleared() {
		ItsmIncidentRecord record = parser.parse(single(incident -> incident.put("solution", "Fixed")))
				.records().getFirst();
		assertThat(record.solution()).isNull();
		assertThat(record.raw()).containsEntry("solution", null);
	}

	@Test
	void activeIncidentWithCompletionDateIsRejected() {
		assertRecordError(single(record -> record.put("completionDate", "2026-11-21T10:00:00Z")), 0,
				"completionDate");
	}

	// --- completion of finished states ------------------------------------------------------

	@Test
	void completedIncidentWithoutCompletionDateIsRejected() {
		assertRecordError(single(record -> record.putAll(Map.of(
				"stateEnum", "Resolved",
				"solution", "Fixed"))), 0, "completionDate");
	}

	@Test
	void completedIncidentWithoutSolutionIsRejected() {
		assertRecordError(single(record -> record.putAll(Map.of(
				"stateEnum", "Closed",
				"completionDate", "2026-11-21T10:00:00Z"))), 0, "solution");
	}

	@Test
	void researchCompletionDateIsRequired() {
		assertRecordError(single(record -> record.put("stateEnum", "Research")), 0, "completionDate");
	}

	@Test
	void researchCompletionDateIsPreservedNotAltered() {
		ItsmIncidentSnapshot snapshot = parser.parse(single(record -> record.putAll(Map.of(
				"stateEnum", "Research",
				"solution", "Уточняем у поставщика."))).replace(
				"\"completionDate\":null", "\"completionDate\":\"2026-11-21T09:58:15.250000Z\""));

		ItsmIncidentRecord record = snapshot.records().getFirst();
		assertThat(record.state()).isEqualTo(ItsmIncidentState.Research);
		assertThat(record.completionDate()).isEqualTo(Instant.parse("2026-11-21T09:58:15.250Z"));
		assertThat(record.raw()).containsEntry("completionDate", "2026-11-21T09:58:15.250Z");
	}

	// --- timestamps -------------------------------------------------------------------------

	@ParameterizedTest
	@ValueSource(strings = {
			"\"2026-11-21T12:55:00+03:00\"",
			"\"2026-11-21T09:55:00-05:00\"",
			"\"2026-11-21\"",
			"\"not-a-date\"",
			"1711000000"
	})
	void nonUtcSubmitDateIsRejected(String jsonValue) {
		assertRecordError(rawSubmitDate(jsonValue), 0, "submitDate");
	}

	@Test
	void subMicrosecondPrecisionIsRejected() {
		assertRecordError(write(List.of(named(incident(0),
				Map.of("lastAssignmentToTeamDateTime", "2026-11-21T09:55:00.1234567Z")))), 0,
				"lastAssignmentToTeamDateTime");
	}

	@Test
	void missingSubmitDateIsRejectedButAssignmentTimeIsOptional() {
		assertRecordError(single(record -> record.remove("submitDate")), 0, "submitDate");
		assertThat(parser.parse(single(record -> record.remove("lastAssignmentToTeamDateTime")))
				.records().getFirst().lastAssignmentToTeamDateTime()).isNull();
	}

	// --- payload ----------------------------------------------------------------------------

	@Test
	void topLevelMustBeASingleJsonArray() {
		for (String body : List.of("{}", "[] []", "", "null")) {
			assertThatThrownBy(() -> parser.parse(body))
					.as("payload [%s]", body)
					.isInstanceOfSatisfying(AppException.class, exception ->
							assertThat(exception.getAppError()).isEqualTo(AppErrors.ITSM_INVALID_PAYLOAD));
		}
	}

	@Test
	void malformedJsonIsARejectedPayload() {
		assertThatThrownBy(() -> parser.parse("[{\"id\":"))
				.isInstanceOfSatisfying(AppException.class, exception ->
						assertThat(exception.getAppError()).isEqualTo(AppErrors.ITSM_INVALID_PAYLOAD));
	}

	@Test
	void nonObjectRecordIsAFieldLevelRejection() {
		assertRecordError("[42]", 0, "record");
	}

	// --- helpers ----------------------------------------------------------------------------

	private void assertRecordError(String body, int index, String field) {
		assertRecordError(body, index, field, null);
	}

	private void assertRecordError(String body, int index, String field, String message) {
		assertThatThrownBy(() -> parser.parse(body))
				.as("body %s", body)
				.isInstanceOfSatisfying(ItsmRecordValidationException.class, exception -> {
					assertThat(exception.getAppError()).isEqualTo(AppErrors.ITSM_INVALID_RECORD);
					assertThat(exception.getRecordIndex()).isEqualTo(index);
					assertThat(exception.getField()).isEqualTo(field);
					if (message != null) {
						assertThat(exception.getMessage()).isEqualTo(message);
					}
				});
	}

	private String single(Consumer<Map<String, Object>> change) {
		Map<String, Object> record = incident(0);
		change.accept(record);
		return write(List.of(record));
	}

	/** Serializes one record with its {@code submitDate} replaced by an arbitrary raw JSON value. */
	private String rawSubmitDate(String rawJson) {
		Map<String, Object> record = incident(0);
		record.put("submitDate", "submitDate");
		return write(List.of(record)).replace("\"submitDate\":\"submitDate\"", "\"submitDate\":" + rawJson);
	}

	private String write(List<Map<String, Object>> records) {
		try {
			return objectMapper.writeValueAsString(records);
		} catch (tools.jackson.core.JacksonException exception) {
			throw new AssertionError(exception);
		}
	}

	private static Map<String, Object> named(Map<String, Object> record, Map<String, Object> changes) {
		record.putAll(changes);
		return record;
	}

	/**
	 * The exact shape the ITSM mock sender emits for one incident, with stable timestamps.
	 */
	private static Map<String, Object> incident(int index) {
		String sysId = String.format(Locale.ROOT, "%012d", 700_000_000L + index);
		var item = new LinkedHashMap<String, Object>();
		item.put("sysId", sysId);
		item.put("id", "INC" + sysId);
		item.put("stateReason", "Нет");
		item.put("stateReasonId", "1");
		item.put("submitDate", SUBMIT_DATE);
		item.put("deadline", null);
		item.put("lastAssignmentToTeamDateTime", SUBMIT_DATE);
		item.put("header", "Оплата · Вход");
		item.put("description", "Вход в сценарии «Вход».");
		item.put("shortDescription", "Вход");
		item.put("assignedGroupName", "Команда клиентских приложений");
		item.put("assignedEmployee", "Дежурный 1 (demo0)");
		item.put("assignedEmployeeLogin", "demo0");
		item.put("initiatorName", "Тестовый мониторинг");
		item.put("initiatorLogin", "monitor-demo");
		item.put("isEffect", false);
		item.put("isReason", false);
		item.put("isDouble", false);
		item.put("isMaster", false);
		item.put("incType", "МИ");
		item.put("serviceTypeId", 30);
		item.put("serviceType", "Технологический");
		item.put("priorityId", 1);
		item.put("urgencyId", 1);
		item.put("urgency", "Низкая");
		item.put("impactId", 2);
		item.put("impact", "Среднее");
		item.put("waitingExitDate", "0001-01-01T00:00:00");
		item.put("isRootIncident", true);
		item.put("productName", "Тестовый магазин");
		item.put("productId", "7000");
		item.put("productMasterId", "BI_7000");
		item.put("productUrl", null);
		item.put("service", "[Тестовый магазин] Оплата");
		item.put("serviceId", "8000");
		item.put("scenarioName", "Вход");
		item.put("isNeedUp", null);
		item.put("eliminationType", null);
		item.put("eliminationTypeId", null);
		item.put("receivingChannel", "Мониторинг");
		item.put("stateEnum", "Assigned");
		item.put("stateId", 0);
		item.put("state", "Назначен");
		item.put("completionDate", null);
		item.put("solution", "");
		return item;
	}

	private static String fixture() {
		var records = new ArrayList<Map<String, Object>>();
		records.add(incident(0));

		records.add(named(incident(1), Map.of(
				"stateEnum", "WorkInProgress", "stateId", 2, "state", "Выполняется", "priorityId", 4)));

		records.add(named(incident(2), Map.of(
				"stateEnum", "Resolved", "stateId", 7, "state", "Завершен",
				"completionDate", "2026-11-21T09:55:30Z", "solution", "Восстановление подтверждено на стенде.")));

		records.add(named(incident(3), Map.of(
				"stateEnum", "Closed", "stateId", 10, "state", "Закрыт",
				"completionDate", "2026-11-21T09:55:45Z", "solution", "Восстановление подтверждено на стенде.")));

		records.add(named(incident(4), Map.of(
				"stateEnum", "Research", "stateId", 20, "state", "Исследование ситуации",
				"completionDate", "2026-11-21T09:56:00Z", "solution", "Восстановление подтверждено на стенде.")));

		return new ObjectMapper().writeValueAsString(records);
	}
}
