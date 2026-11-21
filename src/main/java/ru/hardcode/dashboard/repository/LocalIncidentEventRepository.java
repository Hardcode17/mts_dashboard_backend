package ru.hardcode.dashboard.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.OptionalLong;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LocalIncidentEventRepository {

	private final JdbcClient jdbc;

	public LocalIncidentEventRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public OptionalLong append(String incidentId, String kind, String deduplicationKey, Instant observedAt,
			String details) {
		return jdbc.sql("""
				WITH recorded AS (
				    INSERT INTO local_incident_event (event_seq, incident_id, change_revision, kind,
				                                     deduplication_key, observed_at, details)
				    SELECT nextval('itsm_incident_change_event_seq'), ?, revision, ?, ?, ?, CAST(? AS jsonb)
				    FROM dashboard_publication WHERE singleton
				    ON CONFLICT (incident_id, kind, deduplication_key) DO NOTHING
				    RETURNING event_seq
				)
				UPDATE dashboard_publication SET event_seq = dashboard_publication.event_seq + 1
				FROM recorded WHERE singleton
				RETURNING recorded.event_seq
				""").param(incidentId).param(kind).param(deduplicationKey).param(Timestamp.from(observedAt))
				.param(details).query(Long.class).optional().stream().mapToLong(Long::longValue).findFirst();
	}
}
