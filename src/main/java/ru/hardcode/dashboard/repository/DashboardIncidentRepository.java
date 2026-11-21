package ru.hardcode.dashboard.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.hardcode.dashboard.entity.ItsmIncidentState;

@Repository
public class DashboardIncidentRepository {

	private static final String INCIDENTS_FROM = """
			FROM itsm_incident AS incident
			JOIN itsm_snapshot_source AS source ON source.source_id = incident.source_id
			    AND source.observed_version = incident.last_seen_version
			JOIN itsm_catalog_product AS product ON product.id = incident.product_id
			LEFT JOIN itsm_external_group AS team ON team.id = incident.assigned_group_id
			""";

	/** Published incidents with the attributes the KAN-42 similar-incident scoring reads. */
	private static final String SCORING_FROM = """
			FROM itsm_incident AS incident
			JOIN itsm_snapshot_source AS source ON source.source_id = incident.source_id
			    AND source.observed_version = incident.last_seen_version
			JOIN itsm_catalog_product AS product ON product.id = incident.product_id
			LEFT JOIN itsm_catalog_service AS service ON service.id = incident.service_id
			LEFT JOIN itsm_catalog_scenario AS scenario ON scenario.id = incident.scenario_id
			CROSS JOIN LATERAL (
			    SELECT max(string_value) FILTER (WHERE attribute_name = 'header') AS header,
			           max(string_value) FILTER (WHERE attribute_name = 'errorCode') AS error_code,
			           bool_or(boolean_value) FILTER (WHERE attribute_name = 'isDouble') AS is_double,
			           bool_or(boolean_value) FILTER (WHERE attribute_name = 'isMaster') AS is_master
			    FROM itsm_incident_attribute
			    WHERE incident_id = incident.id
			      AND ((value_type = 'STRING' AND attribute_name IN ('header', 'errorCode'))
			        OR (value_type = 'BOOLEAN' AND attribute_name IN ('isDouble', 'isMaster')))
			) AS attributes
			""";

	private static final String SCORING_SELECT = """
			SELECT incident.id, incident.service_id, service.service_key, product.product_key,
			       scenario.scenario_key, incident.state, incident.priority_id, incident.submit_date,
			       attributes.header, attributes.error_code, attributes.is_double, attributes.is_master
			""";

	private final JdbcClient jdbc;

	public DashboardIncidentRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Publication publication() {
		// The singleton publication counters are advanced by the snapshot worker and by
		// coordinator edits alike: event_seq counts published observations, revision is the
		// latest change revision. Skipped deliveries do not advance the sequence.
		return jdbc.sql("""
				SELECT event_seq, revision, (SELECT max(processed_at) FROM itsm_ingest_batch
				       WHERE status = 'SUCCEEDED') AS updated_at
				FROM dashboard_publication WHERE singleton
				""").query((rs, row) -> new Publication(rs.getLong("event_seq"), rs.getLong("revision"), instant(rs, "updated_at"))).single();
	}

	public List<IncidentRow> list() {
		return jdbc.sql("""
				SELECT incident.id, product.product_key, team.group_name, incident.state, incident.priority_id,
				       incident.submit_date, incident.deadline, incident.completion_date,
				       incident.source_data->>'updatedAt' AS source_updated_at,
				       batch.processed_at AS changed_at,
				       greatest(incident.submit_date, incident.last_assignment_to_team_at,
				                incident.completion_date) AS latest_source_date,
				       assignee.assigned_employee
				""" + INCIDENTS_FROM + """
				LEFT JOIN itsm_incident_change AS change ON change.source_id = incident.source_id
				    AND change.incident_id = incident.id AND change.change_revision = incident.last_change_revision
				    AND change.change_kind IN ('UPDATED', 'RETURNED')
				LEFT JOIN itsm_ingest_batch AS batch ON batch.source_id = change.source_id
				    AND batch.source_version = change.source_version
				LEFT JOIN LATERAL (
				    SELECT max(string_value) AS assigned_employee
				    FROM itsm_incident_attribute
				    WHERE incident_id = incident.id AND attribute_name = 'assignedEmployee'
				      AND value_type = 'STRING'
				) AS assignee ON TRUE
				WHERE incident.present_in_source AND incident.id ~ '^INC[0-9]{6,18}$'
				""")
				.query((rs, row) -> new IncidentRow(summary(rs), rs.getString("source_updated_at"),
						instant(rs, "changed_at"), instant(rs, "latest_source_date"),
						rs.getString("assigned_employee"))).list();
	}

	public Optional<CardRow> findCard(String incidentId) {
		return jdbc.sql("""
				SELECT incident.id, product.product_key, team.group_name, incident.state, incident.priority_id,
				       incident.submit_date, incident.deadline, incident.completion_date,
				       incident.last_assignment_to_team_at, incident.solution, service.service_key,
				       attributes.header, attributes.description, attributes.product_master_id,
				       attributes.service_type, attributes.assigned_employee, attributes.assigned_employee_login,
				       attributes.state_reason
				""" + INCIDENTS_FROM + """
				LEFT JOIN itsm_catalog_service AS service ON service.id = incident.service_id
				CROSS JOIN LATERAL (
				    SELECT max(string_value) FILTER (WHERE attribute_name = 'header') AS header,
				           max(string_value) FILTER (WHERE attribute_name = 'description') AS description,
				           max(string_value) FILTER (WHERE attribute_name = 'productMasterId') AS product_master_id,
				           max(string_value) FILTER (WHERE attribute_name = 'serviceType') AS service_type,
				           max(string_value) FILTER (WHERE attribute_name = 'assignedEmployee') AS assigned_employee,
				           max(string_value) FILTER (WHERE attribute_name = 'assignedEmployeeLogin') AS assigned_employee_login,
				           max(string_value) FILTER (WHERE attribute_name = 'stateReason') AS state_reason
				    FROM itsm_incident_attribute
				    WHERE incident_id = incident.id AND value_type = 'STRING'
				      AND attribute_name IN ('header', 'description', 'productMasterId', 'serviceType',
				                             'assignedEmployee', 'assignedEmployeeLogin', 'stateReason')
				) AS attributes
				WHERE incident.present_in_source AND incident.id = :id
				""").param("id", incidentId)
				.query((rs, row) -> new CardRow(summary(rs), rs.getString("header"), rs.getString("description"),
						rs.getString("product_master_id"), rs.getString("service_key"), rs.getString("service_type"),
						rs.getString("assigned_employee"), rs.getString("assigned_employee_login"),
						rs.getString("state_reason"),
						instant(rs, "last_assignment_to_team_at"), rs.getString("solution")))
				.optional();
	}

	public Optional<ScoringRow> findScoringRow(String incidentId) {
		return jdbc.sql(SCORING_SELECT + SCORING_FROM + """
				WHERE incident.present_in_source AND incident.id = :id
				""").param("id", incidentId)
				.query((rs, row) -> scoringRow(rs)).optional();
	}

	/**
	 * Published incidents of one catalog service, registered in {@code [from, to]} and ordered by
	 * id, so the similar-incident scoring of KAN-42 stays deterministic.
	 */
	public List<ScoringRow> findSimilarScoringRows(long serviceId, String excludedId, Instant from, Instant to) {
		return jdbc.sql(SCORING_SELECT + SCORING_FROM + """
				WHERE incident.present_in_source AND incident.id ~ '^INC[0-9]{6,18}$'
				    AND incident.service_id = :service AND incident.id <> :excluded
				    AND incident.submit_date BETWEEN :from AND :to
				ORDER BY incident.id
				""")
				.param("service", serviceId).param("excluded", excludedId)
				.param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
				.query((rs, row) -> scoringRow(rs)).list();
	}

	private static ScoringRow scoringRow(ResultSet rs) throws SQLException {
		return new ScoringRow(rs.getString("id"), rs.getObject("service_id", Long.class), rs.getString("service_key"),
				rs.getString("product_key"), rs.getString("scenario_key"),
				ItsmIncidentState.valueOf(rs.getString("state")), rs.getInt("priority_id"),
				instant(rs, "submit_date"), rs.getString("header"), rs.getString("error_code"),
				rs.getObject("is_double", Boolean.class), rs.getObject("is_master", Boolean.class));
	}

	/** Current published nodes used by the one-step relationship read. */
	public List<RelationshipNode> findRelationshipNodes(Collection<String> incidentIds) {
		if (incidentIds.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT incident.id, attributes.header, incident.state, incident.priority_id
				FROM itsm_incident AS incident
				JOIN itsm_snapshot_source AS source ON source.source_id = incident.source_id
				    AND source.observed_version = incident.last_seen_version
				LEFT JOIN LATERAL (
				    SELECT max(string_value) FILTER (WHERE attribute_name = 'header') AS header
				    FROM itsm_incident_attribute
				    WHERE incident_id = incident.id AND value_type = 'STRING' AND attribute_name = 'header'
				) AS attributes ON true
				WHERE incident.present_in_source AND incident.id IN (:ids)
				""").param("ids", incidentIds)
				.query((rs, row) -> new RelationshipNode(rs.getString("id"), rs.getString("header"),
						ItsmIncidentState.valueOf(rs.getString("state")), rs.getInt("priority_id")))
				.list();
	}

	public Optional<String> findDirectParentId(String childId) {
		return jdbc.sql("""
				SELECT hierarchy.parent_id
				FROM incident_hierarchies AS hierarchy
				JOIN itsm_incident AS child ON child.id = hierarchy.child_id
				JOIN itsm_incident AS parent ON parent.id = hierarchy.parent_id
				WHERE hierarchy.child_id = :childId AND child.source_id = parent.source_id
				""").param("childId", childId)
				.query(String.class)
				.optional();
	}

	public List<String> findDirectChildIds(String parentId) {
		return jdbc.sql("""
				SELECT hierarchy.child_id
				FROM incident_hierarchies AS hierarchy
				JOIN itsm_incident AS parent ON parent.id = hierarchy.parent_id
				JOIN itsm_incident AS child ON child.id = hierarchy.child_id
				WHERE hierarchy.parent_id = :parentId AND parent.source_id = child.source_id
				ORDER BY hierarchy.child_id
				""").param("parentId", parentId)
				.query(String.class)
				.list();
	}

	private static IncidentSummary summary(ResultSet rs) throws SQLException {
		return new IncidentSummary(rs.getString("id"), rs.getString("product_key"), rs.getString("group_name"),
				ItsmIncidentState.valueOf(rs.getString("state")), rs.getInt("priority_id"),
				instant(rs, "submit_date"), instant(rs, "deadline"), instant(rs, "completion_date"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	public record Publication(long eventSeq, long revision, Instant updatedAt) {
	}

	public record IncidentSummary(String id, String productName, String assignedGroupName, ItsmIncidentState state,
			int priorityId, Instant submitDate, Instant deadline, Instant completionDate) {
	}

	public record IncidentRow(IncidentSummary summary, String sourceUpdatedAt, Instant changedAt,
			Instant latestSourceDate, String assignedEmployee) {
	}

	public record CardRow(IncidentSummary summary, String header, String description, String productMasterId,
			String service, String serviceType, String assignedEmployee, String assignedEmployeeLogin,
			String stateReason, Instant lastAssignmentToTeamDateTime, String solution) {
	}

	/** One published incident with the fields the similar-incident scoring compares (KAN-42). */
	public record ScoringRow(String id, Long serviceId, String serviceKey, String productKey, String scenarioKey,
			ItsmIncidentState state, int priorityId, Instant submitDate, String header, String errorCode,
			Boolean isDouble, Boolean isMaster) {
	}

	public record RelationshipNode(String id, String title, ItsmIncidentState stateEnum, Integer priorityId) {
	}
}
