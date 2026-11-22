package ru.hardcode.dashboard.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.hardcode.dashboard.dto.ConfirmedIncidentLinkResponse;

@Repository
public class IncidentLinkRepository {

	private static final String SELECT_LINKS = """
			SELECT id, source_incident_id, target_incident_id, kind, confirmed_by_user_id, created_at
			FROM incident_links
			""";

	private final JdbcClient jdbc;

	public IncidentLinkRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public ConfirmedIncidentLinkResponse confirm(String source, String target, String kind, long userId) {
		jdbc.sql("""
				INSERT INTO incident_links (source_incident_id, target_incident_id, kind, confirmed_by_user_id)
				VALUES (?, ?, ?, ?)
				ON CONFLICT (source_incident_id, target_incident_id, kind) DO NOTHING
				""").param(source).param(target).param(kind).param(userId).update();
		// A separate READ COMMITTED statement sees the row from the winning concurrent insert.
		return jdbc.sql(SELECT_LINKS + "WHERE source_incident_id = ? AND target_incident_id = ? AND kind = ?")
				.param(source).param(target).param(kind).query(IncidentLinkRepository::map).single();
	}

	public List<ConfirmedIncidentLinkResponse> findForIncident(String incidentId) {
		return jdbc.sql(SELECT_LINKS + "WHERE source_incident_id = :id OR target_incident_id = :id ORDER BY id")
				.param("id", incidentId).query(IncidentLinkRepository::map).list();
	}

	private static ConfirmedIncidentLinkResponse map(ResultSet row, int index) throws SQLException {
		return new ConfirmedIncidentLinkResponse(row.getLong("id"), row.getString("source_incident_id"),
				row.getString("target_incident_id"), row.getString("kind"), row.getLong("confirmed_by_user_id"),
				row.getTimestamp("created_at").toInstant());
	}
}
