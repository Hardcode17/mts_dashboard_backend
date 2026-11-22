package ru.hardcode.dashboard.service.mcpdashboard;

import java.time.Instant;
import java.util.List;

/**
 * DTOs of the MCP Dashboard contract v1.0.0 (KAN-86). Field names and shapes mirror
 * {@code Hardcode17/mts_dashboard_data/docs/mcp/v1/contract.schema.json}; records keep
 * {@code null} fields explicit because the schema requires every key of each object.
 */
final class McpDashboardContract {

	private McpDashboardContract() {
	}

	static final String CONTRACT_VERSION = "1.0.0";

	/** One data source of the response meta with its freshness verdict. */
	record Source(String sourceId, Instant asOf, String status) {
	}

	/** Freshness, sources, completeness and warnings of one response. */
	record Meta(Instant generatedAt, List<Source> sources, String completeness, List<String> warnings) {
	}

	/** List row of one published incident; all schema fields are always present. */
	record Incident(String incidentId, String title, String state, int priorityId, String serviceId,
			String siteId, String assignedGroupId, String assignedPersonId, Instant deadline, Instant updatedAt) {
	}

	/** Full card of one incident; description and solution may be truncated (partial). */
	record Card(String incidentId, String title, String state, int priorityId, String serviceId,
			String siteId, String assignedGroupId, String assignedPersonId, Instant deadline, Instant updatedAt,
			String description, String solution) {
	}

	/** One journaled change of one INC in the contract event shape. */
	record Event(String eventId, String eventSeq, String sourceId, String incidentId, String sourceVersion,
			String changeRevision, String kind, Instant observedAt, Instant sourceTime, List<String> changedFields) {
	}

	/** Attribution of one finding: history observation or directory entry. */
	record Evidence(String kind, String sourceRef, Instant observedAt, String summary) {
	}

	/** One completed analogue with its matched signals and historical resolver. */
	record Analogue(Incident incident, String resolutionSummary, List<String> matchedSignals,
			List<String> differences, String historicalResolverGroupId, Evidence evidence) {
	}

	/** One resolver group candidate with its responsibility verdict. */
	record Group(String groupId, String name, String responsibility, List<Evidence> evidence) {
	}

	/** One external ITSM assignee of one group; competence and duty stay unknown without a source. */
	record Assignee(String personId, String displayName, String groupId, String competence, String onDuty,
			List<Evidence> evidence) {
	}

	/** Data part of {@code list_incidents}. */
	record ListIncidentsData(List<Incident> items, String nextCursor, String snapshotId, String checkpoint) {
	}

	/** Data part of {@code list_incident_changes}. */
	record ChangesData(List<Event> events, String nextCursor, boolean hasMore) {
	}

	/** Data part of {@code find_similar_incidents}. */
	record SimilarData(List<Analogue> items, String nextCursor) {
	}

	/** Data part of {@code find_resolver_groups}. */
	record ResolverGroupsData(List<Group> items, String nextCursor, String recommendedGroupId, String reason) {
	}

	/** Data part of {@code find_assignees}. */
	record AssigneesData(List<Assignee> items, String nextCursor) {
	}

	/** Parsed and validated {@code filters} argument shared by the list-like tools. */
	record Filters(String query, List<String> states, List<Integer> priorityIds, String serviceId,
			String siteId, String assignedGroupId, Boolean unassigned) {

		boolean isEmpty() {
			return query == null && states == null && priorityIds == null && serviceId == null
					&& siteId == null && assignedGroupId == null && unassigned == null;
		}

		/** Stable key inside the cursor binding: changed filters require a new walk. */
		String key() {
			return (query == null ? "" : query) + "\u0001" + (states == null ? "" : String.join(",", states))
					+ "\u0002" + (priorityIds == null ? "" : priorityIds.stream().sorted().map(String::valueOf)
							.reduce((a, b) -> a + "," + b).orElse(""))
					+ "\u0003" + (serviceId == null ? "" : serviceId)
					+ "\u0004" + (siteId == null ? "" : siteId)
					+ "\u0005" + (assignedGroupId == null ? "" : assignedGroupId)
					+ "\u0006" + (unassigned == null ? "" : unassigned.toString());
		}
	}
}
