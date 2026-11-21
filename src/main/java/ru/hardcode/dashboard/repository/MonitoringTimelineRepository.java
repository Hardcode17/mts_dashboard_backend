package ru.hardcode.dashboard.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Durable per-publication history of monitoring category counts (KAN-62). Observations are
 * appended in the ITSM publication transaction; the read side only returns the bounded window
 * in chronological order.
 */
@Repository
public class MonitoringTimelineRepository {

	private final JdbcClient jdbc;

	public MonitoringTimelineRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void append(UUID batchId, Observation observation) {
		jdbc.sql("""
				INSERT INTO itsm_monitoring_observation
				    (batch_id, observed_at, critical_count, high_count, coordinator_count, research_count)
				VALUES (?, ?, ?, ?, ?, ?)
				ON CONFLICT (batch_id) DO NOTHING
				""")
				.param(batchId)
				.param(Timestamp.from(observation.at()))
				.param(observation.critical())
				.param(observation.high())
				.param(observation.coordinators())
				.param(observation.research())
				.update();
	}

	public List<Observation> between(Instant from, Instant to) {
		return jdbc.sql("""
				SELECT observed_at, critical_count, high_count, coordinator_count, research_count
				FROM itsm_monitoring_observation
				WHERE observed_at >= ? AND observed_at <= ?
				ORDER BY observed_at, id
				""")
				.param(Timestamp.from(from))
				.param(Timestamp.from(to))
				.query((rs, row) -> new Observation(rs.getTimestamp("observed_at").toInstant(),
						rs.getInt("critical_count"), rs.getInt("high_count"),
						rs.getInt("coordinator_count"), rs.getInt("research_count")))
				.list();
	}

	public void deleteBefore(Instant cutoff) {
		jdbc.sql("DELETE FROM itsm_monitoring_observation WHERE observed_at < ?")
				.param(Timestamp.from(cutoff))
				.update();
	}

	public record Observation(Instant at, int critical, int high, int coordinators, int research) {
	}
}
