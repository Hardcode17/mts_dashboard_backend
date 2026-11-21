package ru.hardcode.dashboard.repository;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import ru.hardcode.dashboard.service.ItsmIncidentChangeFeed;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Stand-in adapter of {@link ItsmIncidentChangeFeed} that reads the durable INC journal
 * ({@code itsm_incident_change}, written by the snapshot publication and the coordinator edit
 * path) directly. KAN-28 replaces the internals with its cursor-based history behind the same
 * port; KAN-25 detection and the shift event queue keep their contract unchanged.
 */
@Repository
@Transactional(readOnly = true)
public class JdbcItsmIncidentChangeFeed implements ItsmIncidentChangeFeed {

	private static final TypeReference<Map<String, Object>> RECORD = new TypeReference<>() {};

	private final JdbcTemplate jdbc;
	private final ObjectMapper json;

	public JdbcItsmIncidentChangeFeed(JdbcTemplate jdbc, ObjectMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	@Override
	public List<IncidentChange> afterRevision(long afterRevision, int limit) {
		return jdbc.query("""
				SELECT change_revision, source_id, source_version, incident_id, change_kind,
				       before_data, after_data
				FROM itsm_incident_change
				WHERE change_revision > ?
				ORDER BY change_revision, source_id, incident_id
				LIMIT ?
				""", (rs, n) -> new IncidentChange(
				rs.getLong("change_revision"),
				rs.getString("source_id"),
				rs.getLong("source_version"),
				rs.getString("incident_id"),
				ChangeKind.valueOf(rs.getString("change_kind")),
				record(rs.getString("before_data")),
				record(rs.getString("after_data"))),
				afterRevision, limit);
	}

	private Map<String, Object> record(String data) {
		return data == null ? null : json.readValue(data, RECORD);
	}
}
