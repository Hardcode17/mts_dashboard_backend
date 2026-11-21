package ru.hardcode.dashboard.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import ru.hardcode.dashboard.dto.ItsmIncidentRecord;
import ru.hardcode.dashboard.dto.ItsmIncidentSnapshot;
import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.AppException;

/**
 * Resolves the imported incident hierarchy of one complete snapshot. A missing {@code parentId}
 * keeps the relation already imported for that child; an explicit null removes it; a string
 * replaces it. The final candidate is validated as a whole, so row order cannot hide a
 * self-reference, a cycle or a parent that is not part of the same snapshot/source.
 */
@Component
public class ItsmIncidentHierarchyResolver {

	/**
	 * @param snapshot complete validated snapshot
	 * @param imported existing source-owned relations keyed by child id
	 * @return the complete relation set to publish, keyed by child id
	 * @throws AppException {@link AppErrors#ITSM_INVALID_HIERARCHY} for an invalid final graph
	 */
	public Hierarchy resolve(ItsmIncidentSnapshot snapshot, Map<String, String> imported) {
		Set<String> present = snapshot.ids();
		var candidate = new LinkedHashMap<String, String>();
		imported.forEach((childId, parentId) -> {
			// Links of incidents removed by this full snapshot are not part of the new graph.
			// Retain the child’s existing link until it explicitly clears or replaces it.
			if (present.contains(childId)) {
				candidate.put(childId, parentId);
			}
		});
		for (ItsmIncidentRecord record : snapshot.records()) {
			if (!record.raw().containsKey(ItsmIncidentSnapshotParser.FIELD_PARENT_ID)) {
				continue;
			}
			Object value = record.raw().get(ItsmIncidentSnapshotParser.FIELD_PARENT_ID);
			if (value == null) {
				candidate.remove(record.id());
			} else if (value instanceof String parentId) {
				candidate.put(record.id(), parentId);
			} else {
				throw invalid("parentId must be a string or null");
			}
		}
		candidate.forEach((childId, parentId) -> {
			if (!present.contains(parentId)) {
				throw invalid("parentId must reference an incident in the same snapshot");
			}
			if (childId.equals(parentId)) {
				throw invalid("an incident cannot be its own parent");
			}
		});
		candidate.keySet().forEach(childId -> assertNoCycle(childId, candidate));
		return new Hierarchy(Collections.unmodifiableMap(candidate));
	}

	private static void assertNoCycle(String childId, Map<String, String> candidate) {
		var path = new java.util.LinkedHashSet<String>();
		String current = childId;
		while (current != null) {
			if (!path.add(current)) {
				throw invalid("parent links must not form a cycle");
			}
			current = candidate.get(current);
		}
	}

	private static AppException invalid(String reason) {
		return new AppException(AppErrors.ITSM_INVALID_HIERARCHY, Map.of("reason", reason));
	}

	public record Hierarchy(Map<String, String> linksByChildId) {
	}
}
