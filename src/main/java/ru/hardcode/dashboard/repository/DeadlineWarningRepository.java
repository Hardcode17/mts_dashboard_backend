package ru.hardcode.dashboard.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.hardcode.dashboard.dto.DeadlineWarningRuleResponse;

@Repository
public class DeadlineWarningRepository {

	private final JdbcClient jdbc;

	public DeadlineWarningRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public DeadlineWarningRuleResponse rule() {
		return jdbc.sql("SELECT threshold_seconds, version FROM deadline_warning_rule WHERE singleton")
				.query(DeadlineWarningRuleResponse.class).single();
	}

	public DeadlineWarningRuleResponse setThreshold(int thresholdSeconds) {
		return jdbc.sql("""
				UPDATE deadline_warning_rule
				SET threshold_seconds = :seconds,
				    version = version + CASE WHEN threshold_seconds = :seconds THEN 0 ELSE 1 END
				WHERE singleton
				RETURNING threshold_seconds, version
				""").param("seconds", thresholdSeconds).query(DeadlineWarningRuleResponse.class).single();
	}

	public List<Candidate> pending(Instant now, DeadlineWarningRuleResponse rule) {
		return jdbc.sql("""
				WITH in_window AS (
				    SELECT id, deadline, concat((extract(epoch FROM deadline) * 1000000)::bigint, ':', :version) AS key
				    FROM itsm_incident
				    WHERE present_in_source AND last_seen_version IS NOT NULL
				        AND state IN ('Assigned', 'WorkInProgress')
				        AND deadline > :now AND deadline <= :threshold
				)
				SELECT id, deadline, key FROM in_window
				WHERE NOT EXISTS (
				    SELECT 1 FROM local_incident_event
				    WHERE incident_id = in_window.id AND kind = 'DEADLINE_APPROACHING'
				        AND deduplication_key = in_window.key
				)
				ORDER BY deadline, id
				""").param("version", rule.version()).param("now", Timestamp.from(now))
				.param("threshold", Timestamp.from(now.plusSeconds(rule.thresholdSeconds())))
				.query((row, index) -> new Candidate(row.getString("id"), row.getTimestamp("deadline").toInstant(),
						row.getString("key"))).list();
	}

	public record Candidate(String incidentId, Instant deadline, String key) {
	}
}
