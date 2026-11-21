package ru.hardcode.dashboard.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ItsmSnapshotPublicationRepository {

	private final JdbcTemplate jdbc;

	public ItsmSnapshotPublicationRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public SourceHead lockSource(String sourceId) {
		jdbc.update("INSERT INTO itsm_snapshot_source (source_id) VALUES (?) ON CONFLICT DO NOTHING", sourceId);
		return jdbc.queryForObject("""
				SELECT observed_version, change_revision FROM itsm_snapshot_source
				WHERE source_id = ? FOR UPDATE
				""", (rs, n) -> new SourceHead(
				rs.getObject("observed_version", Long.class), rs.getObject("change_revision", Long.class)), sourceId);
	}

	public Long firstUnsettledVersion(String sourceId, Long observed, long incoming) {
		if (observed == null) {
			// A new source can begin at any version; don't jump over an already accepted earlier batch.
			return jdbc.queryForObject("""
					SELECT min(source_version) FROM itsm_ingest_batch
					WHERE source_id = ? AND source_version < ? AND status <> 'SUCCEEDED' AND NOT validation_rejected
					""", Long.class, sourceId, incoming);
		}
		if (incoming == observed + 1) {
			return null;
		}
		List<Long> rejected = jdbc.queryForList("""
				SELECT source_version FROM itsm_ingest_batch
				WHERE source_id = ? AND source_version > ? AND source_version < ? AND validation_rejected
				ORDER BY source_version
				""", Long.class, sourceId, observed, incoming);
		long expected = observed + 1;
		for (long version : rejected) {
			if (version != expected) {
				return expected;
			}
			expected++;
		}
		return expected == incoming ? null : expected;
	}

	public boolean hasForeignIncident(String sourceId, String snapshot) {
		return Boolean.TRUE.equals(jdbc.queryForObject("""
				SELECT EXISTS (
				    SELECT 1 FROM jsonb_array_elements(?::jsonb) AS doc
				    JOIN itsm_incident AS incident ON incident.id = doc->>'id'
				    WHERE incident.source_id IS NOT NULL AND incident.source_id <> ?
				)
				""", Boolean.class, snapshot, sourceId));
	}

	public byte[] legacyPayload(String sourceId, long observedVersion) {
		return jdbc.query("""
				SELECT batch.payload FROM itsm_ingest_batch AS batch
				WHERE batch.source_id = ? AND batch.source_version = ?
				  AND EXISTS (SELECT 1 FROM itsm_incident WHERE source_id = ? AND source_data IS NULL)
				""", (rs, n) -> rs.getBytes("payload"), sourceId, observedVersion, sourceId).stream()
				.findFirst().orElse(null);
	}

	public void restoreLegacySourceData(String sourceId, String snapshot) {
		jdbc.update("""
				UPDATE itsm_incident AS incident SET source_data = doc
				FROM jsonb_array_elements(?::jsonb) AS doc
				WHERE incident.source_id = ? AND incident.id = doc->>'id' AND incident.source_data IS NULL
				""", snapshot, sourceId);
	}

	/**
	 * Diff normalized JSON as JSONB so property order and equivalent numeric values do not produce
	 * changes. A complete observation updates last_seen_version even when the change revision stays
	 * unchanged. An absent row keeps the last observed fields and local state.
	 *
	 * <p>Every journaled row (KAN-28) also commits the observation time, the optional source-side
	 * time of the record ({@code sourceTimes} maps incident id to an ISO instant), and a monotonic
	 * {@code event_seq} from a dedicated sequence, independent of the change revision. Inside one
	 * publication the sequence is assigned in incident id order through the sorted materialized CTE.
	 */
	public List<Change> journalChanges(String sourceId, long version, String snapshot, Instant observedAt,
			String sourceTimes) {
		return jdbc.query("""
				WITH incoming AS (
				    SELECT doc->>'id' AS incident_id, doc FROM jsonb_array_elements(?::jsonb) AS doc
				), old AS (
				    SELECT id AS incident_id, source_data, present_in_source,
				           CASE WHEN source_data IS NULL THEN
				               jsonb_build_object('id', id, 'stateEnum', state, 'priorityId', priority_id,
				                   'submitDate', submit_date, 'lastAssignmentToTeamDateTime', last_assignment_to_team_at,
				                   'deadline', deadline, 'completionDate', completion_date,
				                   'waitingExitDate', waiting_exit_date, 'solution', solution)
				               || coalesce((SELECT jsonb_object_agg(attribute_name,
				                       CASE value_type WHEN 'NUMBER' THEN to_jsonb(number_value)
				                           WHEN 'BOOLEAN' THEN to_jsonb(boolean_value) ELSE to_jsonb(string_value) END)
				                   FROM itsm_incident_attribute WHERE incident_id = itsm_incident.id), '{}'::jsonb)
				           END AS legacy_data
				    FROM itsm_incident WHERE source_id = ?
				), changes AS MATERIALIZED (
				    SELECT coalesce(incoming.incident_id, old.incident_id) AS incident_id,
				           CASE WHEN old.incident_id IS NULL THEN 'ADDED'
				                WHEN incoming.incident_id IS NULL THEN 'MISSING'
				                WHEN NOT old.present_in_source THEN 'RETURNED'
				                ELSE 'UPDATED' END AS kind,
				           CASE WHEN old.present_in_source THEN coalesce(old.source_data, old.legacy_data) END AS before_data,
				           incoming.doc AS after_data
				    FROM incoming FULL JOIN old USING (incident_id)
				    WHERE (incoming.incident_id IS NOT NULL AND (
				               NOT coalesce(old.present_in_source, false) OR old.source_data IS DISTINCT FROM incoming.doc
				           )) OR (incoming.incident_id IS NULL AND old.present_in_source)
				), revision AS MATERIALIZED (
				    SELECT nextval('itsm_snapshot_change_revision') AS value WHERE EXISTS (SELECT 1 FROM changes)
				), source_times AS MATERIALIZED (
				    SELECT key AS incident_id, (value #>> '{}')::timestamptz AS source_time
				    FROM jsonb_each(?::jsonb)
				), sorted AS MATERIALIZED (
				    SELECT changes.incident_id, revision.value AS change_revision, kind, before_data, after_data,
				           source_times.source_time
				    FROM changes CROSS JOIN revision
				    LEFT JOIN source_times ON source_times.incident_id = changes.incident_id
				    ORDER BY changes.incident_id
				)
				INSERT INTO itsm_incident_change
				    (source_id, incident_id, source_version, change_revision, event_seq, change_kind,
				     before_data, after_data, observed_at, source_time)
				SELECT ?, sorted.incident_id, ?, sorted.change_revision, nextval('itsm_incident_change_event_seq'),
				       sorted.kind, sorted.before_data, sorted.after_data, ?, sorted.source_time
				FROM sorted
				RETURNING incident_id, change_revision, change_kind
				""", (rs, n) -> new Change(rs.getString("incident_id"), rs.getLong("change_revision"),
					rs.getString("change_kind").equals("MISSING")),
				snapshot, sourceId, sourceTimes, sourceId, version, Timestamp.from(observedAt));
	}

	public long nextRevision() {
		return jdbc.queryForObject("SELECT nextval('itsm_snapshot_change_revision')", Long.class);
	}

	public boolean isStorableSnapshot(String snapshot) {
		return Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_input_is_valid(?, 'jsonb')", Boolean.class, snapshot));
	}

	public java.util.Optional<Long> dashboardRevision() {
        return jdbc.queryForList("SELECT revision FROM dashboard_publication WHERE singleton AND revision > 0",Long.class)
                .stream().findFirst();
    }

    public void advanceDashboard(long revision) {
        jdbc.update("UPDATE dashboard_publication SET revision=?, event_seq=event_seq+1 WHERE singleton",revision);
    }

    public void updateObservation(String sourceId, long version, long revision, String snapshot) {
		jdbc.update("""
				UPDATE itsm_incident AS incident
				SET source_data = doc, present_in_source = true, last_seen_version = ?,
				    last_change_revision = CASE WHEN NOT incident.present_in_source
				                 OR incident.source_data IS DISTINCT FROM doc
				                 THEN ? ELSE incident.last_change_revision END
				FROM jsonb_array_elements(?::jsonb) AS doc
				WHERE incident.source_id = ? AND incident.id = doc->>'id'
				""", version, revision, snapshot, sourceId);
		jdbc.update("""
				UPDATE itsm_incident
				SET present_in_source = false, last_change_revision = ?
				WHERE source_id = ? AND present_in_source AND (last_seen_version IS NULL OR last_seen_version < ?)
				""", revision, sourceId, version);
		jdbc.update("""
				UPDATE itsm_snapshot_source SET observed_version = ?, change_revision = ? WHERE source_id = ?
				""", version, revision, sourceId);
		advanceDashboard(revision);
	}

	public SourceStatus sourceStatus(String sourceId) {
		return jdbc.queryForObject("""
				SELECT (SELECT observed_version FROM itsm_snapshot_source WHERE source_id = ?) AS observed_version,
				       (SELECT change_revision FROM itsm_snapshot_source WHERE source_id = ?) AS change_revision,
				       (SELECT min(queued.waiting_for_version) FROM itsm_ingest_batch AS queued
				        WHERE queued.source_id = ? AND queued.waiting_for_version IS NOT NULL AND NOT EXISTS (
				            SELECT 1 FROM itsm_ingest_batch AS predecessor
				            WHERE predecessor.source_id = queued.source_id
				              AND predecessor.source_version = queued.waiting_for_version
				              AND (predecessor.status = 'SUCCEEDED' OR predecessor.validation_rejected)
				        )) AS waiting_for_version
				""", (rs, n) -> new SourceStatus(
				rs.getObject("observed_version", Long.class), rs.getObject("change_revision", Long.class),
				rs.getObject("waiting_for_version", Long.class)), sourceId, sourceId, sourceId);
	}

	public List<CommittedChange> findChangesAfter(long revision, int limit) {
		return jdbc.query("""
				SELECT change_revision, source_id, source_version, array_agg(incident_id) AS incident_ids
				FROM itsm_incident_change WHERE change_revision > ?
				GROUP BY change_revision, source_id, source_version
				ORDER BY change_revision LIMIT ?
				""", (rs, n) -> new CommittedChange(rs.getString("source_id"), rs.getLong("source_version"),
				rs.getLong("change_revision"), Set.of((String[]) rs.getArray("incident_ids").getArray())), revision, limit);
	}

	public record SourceHead(Long observedVersion, Long changeRevision) {
	}

	public record Change(String incidentId, long revision, boolean missing) {
	}

	public record SourceStatus(Long observedVersion, Long changeRevision, Long waitingForVersion) {
	}

	public record CommittedChange(String sourceId, long sourceVersion, long revision, Set<String> incidentIds) {
	}
}
