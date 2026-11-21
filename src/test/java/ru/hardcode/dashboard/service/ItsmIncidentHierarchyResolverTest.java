package ru.hardcode.dashboard.service;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.AppException;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused KAN-66 graph rules. The final candidate is independent of row order and is validated
 * only after every row has been applied.
 */
class ItsmIncidentHierarchyResolverTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private ItsmIncidentHierarchyResolver resolver;
	private ItsmIncidentSnapshotParser parser;

	@BeforeEach
	void setUp() {
		resolver = new ItsmIncidentHierarchyResolver();
		parser = new ItsmIncidentSnapshotParser(objectMapper);
	}

	@Test
	void resolvesAChildListedBeforeItsParent() {
		var snapshot = parser.parse(snapshot(
				incident("INC000000002", "\"INC000000001\""),
				incident("INC000000001", null),
				incident("INC000000003", "\"INC000000001\"")));

		assertThat(resolver.resolve(snapshot, Map.of()).linksByChildId())
				.containsExactlyInAnyOrderEntriesOf(Map.of(
						"INC000000002", "INC000000001",
						"INC000000003", "INC000000001"));
	}

	@Test
	void absentKeepsTheImportedRelationAndExplicitNullRemovesIt() {
		var snapshot = parser.parse(snapshot(
				incident("INC000000001", null),
				incident("INC000000002", null),
				incident("INC000000003", "null")));

		assertThat(resolver.resolve(snapshot,
				Map.of("INC000000002", "INC000000001", "INC000000003", "INC000000001")).linksByChildId())
				.containsExactlyInAnyOrderEntriesOf(Map.of("INC000000002", "INC000000001"));
	}

	@Test
	void refusesToSilentlyRemoveAnOmittedLinkWhenTheParentDisappears() {
		var retained = Map.of("INC000000002", "INC000000001");
		var omitted = parser.parse(snapshot(incident("INC000000002", null)));
		var cleared = parser.parse(snapshot(incident("INC000000002", "null")));

		assertThatThrownBy(() -> resolver.resolve(omitted, retained))
				.isInstanceOfSatisfying(AppException.class, exception ->
						assertThat(exception.getAppError()).isEqualTo(AppErrors.ITSM_INVALID_HIERARCHY));
		assertThat(resolver.resolve(cleared, retained).linksByChildId()).isEmpty();
	}

	@Test
	void rejectsSelfReference() {
		assertRejected(snapshot(
				incident("INC000000002", "\"INC000000002\"")), "own parent");
	}

	@Test
	void rejectsACycleWhateverTheRowOrder() {
		assertRejected(snapshot(
				incident("INC000000002", "\"INC000000003\""),
				incident("INC000000003", "\"INC000000002\"")), "cycle");
	}

	@Test
	void rejectsAnUnknownParentWhateverTheRowOrder() {
		assertRejected(snapshot(
				incident("INC000000002", "\"INC999999999\""),
				incident("INC000000003", null)), "same snapshot");
	}

	private void assertRejected(String body, String reason) {
		assertThatThrownBy(() -> resolver.resolve(parser.parse(body), Map.of()))
				.isInstanceOfSatisfying(AppException.class, exception -> {
					assertThat(exception.getAppError()).isEqualTo(AppErrors.ITSM_INVALID_HIERARCHY);
					assertThat(exception.getMessage()).contains(reason);
				});
	}

	private static String snapshot(String... records) {
		return "[" + String.join(",", records) + "]";
	}

	private static String incident(String id, String parentIdJson) {
		String parentField = parentIdJson == null ? "" : ",\"parentId\":" + parentIdJson;
		return """
				{"id":"%s","stateEnum":"Assigned","priorityId":4,"submitDate":"2026-11-21T09:00:00Z",
				 "productName":"Graph product","solution":null%s}
				""".formatted(id, parentField);
	}
}
