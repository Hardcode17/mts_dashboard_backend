package ru.hardcode.dashboard.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.hardcode.dashboard.service.deadlinerisk.DeadlineRiskLevel;

/**
 * KAN-82 deadline risk reads and writes. Feature values come from the published snapshot and the
 * change journal only; the stored rows are the last full scoring pass.
 */
@Repository
public class DeadlineRiskRepository {

	private final JdbcClient jdbc;

	public DeadlineRiskRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Serialize refreshes across application instances before reading the published data. */
	public void lockRefresh() {
		jdbc.sql("SELECT pg_advisory_xact_lock(537395202)").query().singleRow();
	}

	/**
	 * Median resolution time of completed published incidents, in hours. Service × priority ×
	 * group is the primary scope; group × priority and then group are sparse-history fallbacks.
	 * Incidents completed before registration are excluded.
	 */
	public ResolutionMedians findResolutionMedians() {
		String from = """
				FROM itsm_incident AS incident
				JOIN itsm_snapshot_source AS source ON source.source_id = incident.source_id
				    AND source.observed_version = incident.last_seen_version
				JOIN itsm_external_group AS team ON team.id = incident.assigned_group_id
				WHERE incident.present_in_source
				    AND incident.state IN ('Resolved', 'Closed', 'Research')
				    AND incident.completion_date IS NOT NULL
				    AND incident.completion_date > incident.submit_date
				""";
		Map<MedianSegment, Double> bySegment = new java.util.HashMap<>();
		jdbc.sql("SELECT incident.service_id, team.group_name, incident.priority_id, " + MEDIAN_EXPRESSION
				+ " " + from + " AND incident.service_id IS NOT NULL"
				+ " GROUP BY incident.service_id, team.group_name, incident.priority_id")
				.query((rs, row) -> bySegment.put(new MedianSegment(rs.getLong("service_id"),
						rs.getString("group_name"), rs.getInt("priority_id")), rs.getDouble("median_hours")))
				.list();
		Map<String, Map<Integer, Double>> byGroupAndPriority = new java.util.HashMap<>();
		jdbc.sql("SELECT team.group_name, incident.priority_id, " + MEDIAN_EXPRESSION + " " + from
				+ " GROUP BY team.group_name, incident.priority_id ORDER BY team.group_name, incident.priority_id")
				.query((rs, row) -> byGroupAndPriority
						.computeIfAbsent(rs.getString("group_name"), group -> new java.util.HashMap<>())
						.put(rs.getInt("priority_id"), rs.getDouble("median_hours")))
				.list();
		Map<String, Double> byGroup = new java.util.HashMap<>();
		jdbc.sql("SELECT team.group_name, " + MEDIAN_EXPRESSION + " " + from
				+ " GROUP BY team.group_name ORDER BY team.group_name")
				.query((rs, row) -> byGroup.put(rs.getString("group_name"), rs.getDouble("median_hours")))
				.list();
		return new ResolutionMedians(bySegment, byGroupAndPriority, byGroup);
	}

	private static final String MEDIAN_EXPRESSION = """
			percentile_cont(0.5) WITHIN GROUP (
			    ORDER BY extract(epoch FROM (incident.completion_date - incident.submit_date)) / 3600.0
			) AS median_hours
			""";

	/** Active published incidents with a deadline and their KAN-82 feature values. */
	public List<ActiveIncident> findActiveWithDeadline() {
		return jdbc.sql("""
				SELECT incident.id, team.group_name, incident.service_id, incident.priority_id, incident.deadline,
				       (SELECT count(*) FROM itsm_incident_change AS change
				         WHERE change.source_id = incident.source_id AND change.incident_id = incident.id
				           AND change.before_data IS NOT NULL AND change.after_data IS NOT NULL
				           AND NULLIF(change.before_data->>'deadline', '') IS NOT NULL
				           AND NULLIF(change.after_data->>'deadline', '') IS NOT NULL
				           AND (change.after_data->>'deadline')::timestamptz
				               > (change.before_data->>'deadline')::timestamptz) AS deadline_extensions,
				       (SELECT count(*) FROM itsm_incident_change AS change
				         WHERE change.source_id = incident.source_id AND change.incident_id = incident.id
				           AND change.before_data IS NOT NULL AND change.after_data IS NOT NULL
				           AND (coalesce(change.before_data->>'assignedGroupName', '')
				                 <> coalesce(change.after_data->>'assignedGroupName', '')
				             OR coalesce(change.before_data->>'assignedEmployeeLogin', '')
				                 <> coalesce(change.after_data->>'assignedEmployeeLogin', '')
				             OR coalesce(change.before_data->>'assignedEmployee', '')
				                 <> coalesce(change.after_data->>'assignedEmployee', ''))) AS reassignments,
				       (SELECT count(*) FROM itsm_incident_change AS change
				         WHERE change.source_id = incident.source_id AND change.incident_id = incident.id
				           AND change.before_data IS NOT NULL AND change.after_data IS NOT NULL
				           AND change.before_data->>'stateEnum' IN ('Resolved', 'Closed', 'Research')
				           AND change.after_data->>'stateEnum' IN ('Assigned', 'WorkInProgress')) AS reopenings,
				       coalesce((SELECT max(change.observed_at) FROM itsm_incident_change AS change
				          WHERE change.source_id = incident.source_id AND change.incident_id = incident.id
				            AND change.before_data IS NOT NULL AND change.after_data IS NOT NULL
				            AND coalesce(change.before_data->>'stateEnum', '')
				                <> coalesce(change.after_data->>'stateEnum', '')),
				           incident.last_assignment_to_team_at, incident.submit_date) AS last_status_change,
				       (SELECT count(*) FROM itsm_incident AS other
				         WHERE other.present_in_source
				           AND other.state IN ('Assigned', 'WorkInProgress')
				           AND other.assigned_person_id IS NOT NULL
				           AND other.assigned_person_id = incident.assigned_person_id) AS assignee_open_incidents
				FROM itsm_incident AS incident
				JOIN itsm_snapshot_source AS source ON source.source_id = incident.source_id
				    AND source.observed_version = incident.last_seen_version
				LEFT JOIN itsm_external_group AS team ON team.id = incident.assigned_group_id
				WHERE incident.present_in_source
				    AND incident.state IN ('Assigned', 'WorkInProgress')
				    AND incident.deadline IS NOT NULL
				ORDER BY incident.id
				""")
				.query((rs, row) -> new ActiveIncident(rs.getString("id"), rs.getString("group_name"),
						rs.getObject("service_id", Long.class),
						rs.getInt("priority_id"), instant(rs, "deadline"), rs.getLong("deadline_extensions"),
						rs.getLong("reassignments"), rs.getLong("reopenings"), instant(rs, "last_status_change"),
						rs.getLong("assignee_open_incidents")))
				.list();
	}

	/** Full refresh: the previous pass stays readable until this replacement commits. */
	public void replaceAll(List<ScoredRisk> scored) {
		jdbc.sql("DELETE FROM deadline_risk").update();
		for (ScoredRisk risk : scored) {
			jdbc.sql("""
					INSERT INTO deadline_risk (incident_id, risk, score, evaluated_at, reasons)
					VALUES (:incidentId, :risk, :score, :evaluatedAt, CAST(:reasons AS jsonb))
					""")
					.param("incidentId", risk.incidentId())
					.param("risk", risk.risk().wireName())
					.param("score", risk.score())
					.param("evaluatedAt", Timestamp.from(risk.evaluatedAt()))
					.param("reasons", risk.reasons())
					.update();
		}
	}

	public Optional<StoredRisk> findStored(String incidentId) {
		return jdbc.sql("""
				SELECT risk, score, evaluated_at, reasons::text AS reasons
				FROM deadline_risk WHERE incident_id = :incidentId
				""")
				.param("incidentId", incidentId)
				.query((rs, row) -> new StoredRisk(DeadlineRiskLevel.ofWire(rs.getString("risk")),
						rs.getObject("score", Double.class), instant(rs, "evaluated_at"), rs.getString("reasons")))
				.optional();
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	public record MedianSegment(long serviceId, String group, int priority) {
	}

	public record ResolutionMedians(Map<MedianSegment, Double> bySegment,
			Map<String, Map<Integer, Double>> byGroupAndPriority,
			Map<String, Double> byGroup) {
	}

	/** Feature values of one active incident with a deadline. */
	public record ActiveIncident(String id, String assignedGroupName, Long serviceId, int priorityId, Instant deadline,
			long deadlineExtensions, long reassignments, long reopenings, Instant lastStatusChange,
			long assigneeOpenIncidents) {
	}

	/** One row of the scoring pass; {@code null} score only with {@link DeadlineRiskLevel#UNKNOWN}. */
	public record ScoredRisk(String incidentId, DeadlineRiskLevel risk, Double score, Instant evaluatedAt,
			String reasons) {
	}

	public record StoredRisk(DeadlineRiskLevel risk, Double score, Instant evaluatedAt, String reasons) {
	}
}
