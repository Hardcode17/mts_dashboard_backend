package ru.hardcode.dashboard.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.hardcode.dashboard.entity.ItsmIncidentState;

@Repository
public class DashboardIncidentRepository {

	private static final String MEETING_LINK_KEY = "MTS Link";

	private static final String INCIDENTS_FROM = """
			FROM itsm_incident AS incident
			JOIN itsm_snapshot_source AS source ON source.source_id = incident.source_id
			    AND source.observed_version = incident.last_seen_version
			JOIN itsm_catalog_product AS product ON product.id = incident.product_id
			LEFT JOIN itsm_external_group AS team ON team.id = incident.assigned_group_id
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
				SELECT event_seq, revision FROM dashboard_publication WHERE singleton
				""").query((rs, row) -> new Publication(rs.getLong("event_seq"), rs.getLong("revision"))).single();
	}

	public List<IncidentRow> list() {
		return jdbc.sql("""
				SELECT incident.id, product.product_key, team.group_name, incident.state, incident.priority_id,
				       incident.submit_date, incident.deadline, incident.completion_date,
				       incident.source_data->>'updatedAt' AS source_updated_at,
				       batch.processed_at AS changed_at,
				       greatest(incident.submit_date, incident.last_assignment_to_team_at,
				                incident.completion_date) AS latest_source_date
				""" + INCIDENTS_FROM + """
				LEFT JOIN itsm_incident_change AS change ON change.source_id = incident.source_id
				    AND change.incident_id = incident.id AND change.change_revision = incident.last_change_revision
				    AND change.change_kind IN ('UPDATED', 'RETURNED')
				LEFT JOIN itsm_ingest_batch AS batch ON batch.source_id = change.source_id
				    AND batch.source_version = change.source_version
				WHERE incident.present_in_source AND incident.id ~ '^INC[0-9]{6,18}$'
				""")
				.query((rs, row) -> new IncidentRow(summary(rs), rs.getString("source_updated_at"),
						instant(rs, "changed_at"), instant(rs, "latest_source_date"))).list();
	}

	public boolean saveMeetingLink(String incidentId, String url) {
		return jdbc.sql("""
				UPDATE itsm_incident AS incident
				SET links_data = jsonb_set(incident.links_data, ARRAY[CAST(:key AS text)], to_jsonb(CAST(:url AS text)))
				WHERE incident.id = :id AND incident.present_in_source AND incident.last_seen_version IS NOT NULL
				""").param("id", incidentId).param("key", MEETING_LINK_KEY).param("url", url).update() == 1;
	}

	public Optional<CardRow> findCard(String incidentId) {
		return jdbc.sql("""
				SELECT incident.id, product.product_key, team.group_name, incident.state, incident.priority_id,
				       incident.submit_date, incident.deadline, incident.completion_date,
				       incident.last_assignment_to_team_at, incident.solution, service.service_key,
				       attributes.header, attributes.description, attributes.product_master_id,
				       attributes.service_type, attributes.assigned_employee, attributes.assigned_employee_login,
				       attributes.state_reason, incident.links_data ->> CAST(:key AS text) AS meeting_url
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
				""").param("id", incidentId).param("key", MEETING_LINK_KEY)
				.query((rs, row) -> new CardRow(summary(rs), rs.getString("header"), rs.getString("description"),
						rs.getString("product_master_id"), rs.getString("service_key"), rs.getString("service_type"),
						rs.getString("assigned_employee"), rs.getString("assigned_employee_login"),
						rs.getString("state_reason"),
						instant(rs, "last_assignment_to_team_at"), rs.getString("solution"), rs.getString("meeting_url")))
				.optional();
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

	public record Publication(long eventSeq, long revision) {
	}

	public record IncidentSummary(String id, String productName, String assignedGroupName, ItsmIncidentState state,
			int priorityId, Instant submitDate, Instant deadline, Instant completionDate) {
	}

	public record IncidentRow(IncidentSummary summary, String sourceUpdatedAt, Instant changedAt, Instant latestSourceDate) {
	}

	public record CardRow(IncidentSummary summary, String header, String description, String productMasterId,
			String service, String serviceType, String assignedEmployee, String assignedEmployeeLogin,
			String stateReason, Instant lastAssignmentToTeamDateTime, String solution, String meetingUrl) {
	}
}
