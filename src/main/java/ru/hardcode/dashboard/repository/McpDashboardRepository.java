package ru.hardcode.dashboard.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only SQL of the MCP Dashboard adapter (KAN-86). Every query reads only the
 * published domain (KAN-17/KAN-55), the durable change journal (KAN-28) and the confirmed
 * official assignments (KAN-32); raw source payloads never leave the dashboard.
 */
@Repository
public class McpDashboardRepository {

	private static final String INCIDENT_COLUMNS = """
			SELECT incident.id, incident.state, incident.priority_id, incident.deadline,
			       incident.source_data->>'updatedAt' AS updated_at,
			       service.service_key AS service_key, product.product_key AS product_key,
			       team.group_name AS group_name, person.login AS person_login,
			       header.header AS header, description.description AS description
			""";

	/** Same published-join as the dashboard reads: only currently present rows count. */
	private static final String INCIDENT_FROM = """
			FROM itsm_incident AS incident
			JOIN itsm_snapshot_source AS source ON source.source_id = incident.source_id
			    AND source.observed_version = incident.last_seen_version
			JOIN itsm_catalog_product AS product ON product.id = incident.product_id
			LEFT JOIN itsm_catalog_service AS service ON service.id = incident.service_id
			LEFT JOIN itsm_external_group AS team ON team.id = incident.assigned_group_id
			CROSS JOIN LATERAL (
			    SELECT max(string_value) AS header FROM itsm_incident_attribute
			    WHERE incident_id = incident.id AND attribute_name = 'header' AND value_type = 'STRING'
			) AS header
			CROSS JOIN LATERAL (
			    SELECT max(string_value) AS description FROM itsm_incident_attribute
			    WHERE incident_id = incident.id AND attribute_name = 'description' AND value_type = 'STRING'
			) AS description
			CROSS JOIN LATERAL (
			    SELECT max(string_value) AS login FROM itsm_incident_attribute
			    WHERE incident_id = incident.id AND attribute_name = 'assignedEmployeeLogin' AND value_type = 'STRING'
			) AS person
			WHERE incident.present_in_source AND incident.id ~ '^INC[0-9]{6,18}$'
			""";

	private final JdbcClient jdbc;

	public McpDashboardRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Upper bound of the durable journal in the caller's transaction. */
	public long maxEventSeq() {
		Long max = jdbc.sql("SELECT coalesce(max(event_seq), 0) FROM itsm_incident_change")
				.query((rs, row) -> rs.getLong(1)).single();
		return max == null ? 0 : max;
	}

	/** Stable identity of the change journal; changes only when the journal is reset. */
	public UUID journalEpoch() {
		return jdbc.sql("SELECT journal_epoch FROM mcp_dashboard_state WHERE singleton")
				.query((rs, row) -> UUID.fromString(rs.getString(1))).single();
	}

	/** One page of published incidents ordered by the contract order. */
	public List<IncidentRow> incidentsPage(IncidentFilter filter, int limit, long offset) {
		var sql = new StringBuilder(INCIDENT_COLUMNS).append(INCIDENT_FROM);
		var conditions = new ArrayList<String>();
		var values = new ArrayList<Object>();
		if (filter.states() != null && !filter.states().isEmpty()) {
			conditions.add("incident.state IN (" + placeholders(filter.states().size()) + ")");
			values.addAll(filter.states());
		}
		if (filter.priorityIds() != null && !filter.priorityIds().isEmpty()) {
			conditions.add("incident.priority_id IN (" + placeholders(filter.priorityIds().size()) + ")");
			values.addAll(filter.priorityIds());
		}
		if (filter.serviceId() != null) {
			conditions.add("service.service_key = ?");
			values.add(filter.serviceId());
		}
		if (filter.assignedGroupId() != null) {
			conditions.add("team.group_name = ?");
			values.add(filter.assignedGroupId());
		}
		if (filter.unassigned() != null) {
			conditions.add(Boolean.TRUE.equals(filter.unassigned())
					? "person.login IS NULL"
					: "person.login IS NOT NULL");
		}
		if (filter.query() != null) {
			conditions.add("(header.header ILIKE ? ESCAPE '\\' OR description.description ILIKE ? ESCAPE '\\')");
			String like = like(filter.query());
			values.add(like);
			values.add(like);
		}
		if (!conditions.isEmpty()) {
			sql.append(" AND ").append(String.join(" AND ", conditions));
		}
		sql.append(" ORDER BY incident.priority_id DESC, incident.id ASC LIMIT ? OFFSET ?");
		values.add(limit);
		values.add(offset);
		return jdbc.sql(sql.toString())
				.params(values.toArray())
				.query(McpDashboardRepository::incidentRow).list();
	}

	public Optional<IncidentRow> incident(String incidentId) {
		return jdbc.sql(INCIDENT_COLUMNS + INCIDENT_FROM + " AND incident.id = ?")
				.param(incidentId)
				.query(McpDashboardRepository::incidentRow).optional();
	}

	public Optional<CardRow> card(String incidentId) {
		return jdbc.sql("""
				SELECT incident.id, incident.state, incident.priority_id, incident.deadline,
				       incident.solution,
				       incident.source_data->>'updatedAt' AS updated_at,
				       service.service_key AS service_key, product.product_key AS product_key,
				       team.group_name AS group_name, person.login AS person_login,
				       header.header AS header, description.description AS description
				""" + INCIDENT_FROM + " AND incident.id = ?")
				.param(incidentId)
				.query((rs, row) -> new CardRow(rs.getString("id"), rs.getString("state"),
						rs.getInt("priority_id"), instant(rs, "deadline"), rs.getString("solution"),
						rs.getString("updated_at"), rs.getString("service_key"), rs.getString("product_key"),
						rs.getString("group_name"), rs.getString("person_login"),
						rs.getString("header"), rs.getString("description"))).optional();
	}

	/**
	 * Completed analogues of one target incident: exact error code first, then the FTS rank
	 * over header, description and solution, ties broken by incident id. Research is never a
	 * candidate even though the dashboard counts it as completed.
	 */
	public List<AnalogueRow> analoguesPage(String incidentId, String[] tokens, List<String> states,
			String serviceId, String assignedGroupId, Boolean unassigned, String query, int limit, long offset) {
		String tsQuery = tokens.length == 0 ? null : String.join(" | ", tokens);
		var sql = new StringBuilder(SQL_ANALOGUES_PREFIX);
		sql.append(" AND c.state IN (").append(placeholders(states.size())).append(")");
		var conditions = new ArrayList<String>();
		var extraValues = new ArrayList<Object>();
		if (serviceId != null) {
			conditions.add("svc.service_key = ?");
			extraValues.add(serviceId);
		}
		if (assignedGroupId != null) {
			conditions.add("team.group_name = ?");
			extraValues.add(assignedGroupId);
		}
		if (unassigned != null) {
			conditions.add(Boolean.TRUE.equals(unassigned) ? "person.login IS NULL" : "person.login IS NOT NULL");
		}
		if (query != null) {
			conditions.add("(hdr2.header ILIKE ? ESCAPE '\\' OR coalesce(c.solution, '') ILIKE ? ESCAPE '\\')");
			String like = like(query);
			extraValues.add(like);
			extraValues.add(like);
		}
		for (String condition : conditions) {
			sql.append(" AND ").append(condition);
		}
		sql.append(" ORDER BY exact_code DESC, rank DESC, c.id ASC LIMIT ? OFFSET ?");
		// Placeholder order in the text: target id, rank tsquery (x2), cv tsquery,
		// WHERE tsquery (x2), then the appended state filter, extra filters, LIMIT, OFFSET.
		var values = new ArrayList<Object>();
		values.add(incidentId);
		values.add(tsQuery);
		values.add(tsQuery);
		values.add(tsQuery);
		values.add(tsQuery);
		values.add(tsQuery);
		values.addAll(states);
		values.addAll(extraValues);
		values.add(limit);
		values.add(offset);
		return jdbc.sql(sql.toString())
				.params(values.toArray())
				.query(McpDashboardRepository::analogueRow).list();
	}

	/** Prefix of the analogues query up to the resolved state placeholders. */
	private static final String SQL_ANALOGUES_PREFIX = """
			WITH target AS (
			    SELECT i.id, i.service_id, i.state, i.priority_id,
			           err.error_code, hdr.header, descr.description
			    FROM itsm_incident i
			    CROSS JOIN LATERAL (SELECT max(string_value) AS header FROM itsm_incident_attribute
			        WHERE incident_id = i.id AND attribute_name = 'header' AND value_type = 'STRING') hdr
			    CROSS JOIN LATERAL (SELECT max(string_value) AS description FROM itsm_incident_attribute
			        WHERE incident_id = i.id AND attribute_name = 'description' AND value_type = 'STRING') descr
			    CROSS JOIN LATERAL (SELECT max(string_value) AS error_code FROM itsm_incident_attribute
			        WHERE incident_id = i.id AND attribute_name = 'errorCode' AND value_type = 'STRING') err
			    WHERE i.present_in_source AND i.id = ?
			)
			SELECT c.id, c.state, c.priority_id, c.deadline, c.submit_date,
			       c.source_data->>'updatedAt' AS updated_at,
			       svc.service_key AS service_key, prod.product_key AS product_key,
			       team.group_name AS group_name, person.login AS person_login,
			       hdr2.header, c.solution,
			       (coalesce(t.error_code, '') <> '' AND coalesce(cerr.error_code, '') <> ''
			          AND t.error_code = cerr.error_code) AS exact_code,
			       CASE WHEN to_tsquery('russian', ?) IS NULL THEN 0 ELSE ts_rank(
			           to_tsvector('russian', coalesce(hdr2.header, '') || ' ' || coalesce(cdescr.description, '') || ' ' || coalesce(c.solution, '')),
			           to_tsquery('russian', ?)) END AS rank
			FROM itsm_incident c
			JOIN itsm_snapshot_source AS csource ON csource.source_id = c.source_id
			    AND csource.observed_version = c.last_seen_version
			JOIN itsm_catalog_product AS prod ON prod.id = c.product_id
			LEFT JOIN itsm_catalog_service AS svc ON svc.id = c.service_id
			LEFT JOIN itsm_external_group AS team ON team.id = c.assigned_group_id
			CROSS JOIN LATERAL (SELECT max(string_value) AS header FROM itsm_incident_attribute
			    WHERE incident_id = c.id AND attribute_name = 'header' AND value_type = 'STRING') hdr2
			CROSS JOIN LATERAL (SELECT max(string_value) AS description FROM itsm_incident_attribute
			    WHERE incident_id = c.id AND attribute_name = 'description' AND value_type = 'STRING') cdescr
			CROSS JOIN LATERAL (SELECT max(string_value) AS login FROM itsm_incident_attribute
			    WHERE incident_id = c.id AND attribute_name = 'assignedEmployeeLogin' AND value_type = 'STRING') person
			CROSS JOIN LATERAL (SELECT max(string_value) AS error_code FROM itsm_incident_attribute
			    WHERE incident_id = c.id AND attribute_name = 'errorCode' AND value_type = 'STRING') cerr
			CROSS JOIN target t
			CROSS JOIN LATERAL (SELECT CASE WHEN to_tsquery('russian', ?) IS NULL THEN NULL
			    ELSE to_tsvector('russian', coalesce(hdr2.header, '') || ' ' || coalesce(cdescr.description, '') || ' ' || coalesce(c.solution, '')) END AS tsv) cv
			WHERE c.present_in_source AND c.id <> t.id AND c.id ~ '^INC[0-9]{6,18}$'
			AND coalesce(btrim(c.solution), '') <> ''
			AND (t.error_code IS NOT NULL AND t.error_code <> '' AND t.error_code = cerr.error_code
			     OR (to_tsquery('russian', ?) IS NOT NULL AND cv.tsv @@ to_tsquery('russian', ?)))			""";

	/** Historical resolver groups: groups behind completed cases of the same service. */
	public List<String> historicalResolverGroups(String incidentId, String serviceKey, int limit, long offset) {
		return jdbc.sql("""
				SELECT g.group_name
				FROM itsm_incident i
				JOIN itsm_external_assignment a ON a.incident_id = i.id
				JOIN itsm_external_group g ON g.id = a.group_id
				JOIN itsm_catalog_service s ON s.id = i.service_id AND s.service_key = ?
				WHERE i.present_in_source AND i.id <> ?
				  AND i.state IN ('Resolved', 'Closed')
				GROUP BY g.group_name
				ORDER BY g.group_name ASC
				LIMIT ? OFFSET ?
				""")
				.params(serviceKey, incidentId, limit, offset)
				.query((rs, row) -> rs.getString(1)).list();
	}

	/** Confirmed official assignments of one incident; several rows mean an ambiguous owner. */
	public List<String> officialAssignments(String incidentId) {
		return jdbc.sql("""
				SELECT DISTINCT a.team
				FROM coordination_official_assignment a
				WHERE a.incident_id = ? AND a.team IS NOT NULL
				ORDER BY a.team
				""")
				.param(incidentId)
				.query((rs, row) -> rs.getString(1)).list();
	}

	private static IncidentRow incidentRow(ResultSet rs, int row) throws SQLException {
		return new IncidentRow(rs.getString("id"), rs.getString("state"), rs.getInt("priority_id"),
				instant(rs, "deadline"), rs.getString("updated_at"), rs.getString("service_key"),
				rs.getString("product_key"), rs.getString("group_name"), rs.getString("person_login"),
				rs.getString("header"), rs.getString("description"));
	}

	private static AnalogueRow analogueRow(ResultSet rs, int row) throws SQLException {
		return new AnalogueRow(rs.getString("id"), rs.getString("state"), rs.getInt("priority_id"),
				instant(rs, "deadline"), rs.getString("updated_at"), instant(rs, "submit_date"),
				rs.getString("service_key"),
				rs.getString("product_key"), rs.getString("group_name"), rs.getString("person_login"),
				rs.getString("header"), rs.getString("solution"), rs.getBoolean("exact_code"),
				rs.getDouble("rank"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	private static String placeholders(int count) {
		return String.join(", ", java.util.Collections.nCopies(count, "?"));
	}

	/** LIKE escape of the user query: literal percent, underscore and backslash. */
	private static String like(String query) {
		return "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
	}

	public record IncidentRow(String id, String state, int priorityId, Instant deadline, String updatedAt,
			String serviceKey, String productKey, String groupName, String personLogin,
			String header, String description) {
	}

	public record CardRow(String id, String state, int priorityId, Instant deadline, String solution,
			String updatedAt, String serviceKey, String productKey, String groupName, String personLogin,
			String header, String description) {
	}

	public record AnalogueRow(String id, String state, int priorityId, Instant deadline, String updatedAt,
			Instant submitDate, String serviceKey, String productKey, String groupName, String personLogin,
			String header, String solution, boolean exactCode, double rank) {
	}

	/** Resolved filter arguments into SQL-friendly values; unsupported site filter never reaches SQL. */
	public record IncidentFilter(List<String> states, List<Integer> priorityIds, String serviceId,
			String assignedGroupId, Boolean unassigned, String query) {
	}
}
