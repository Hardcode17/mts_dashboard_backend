package ru.hardcode.dashboard.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.hardcode.dashboard.entity.AiInvestigationFact;
import ru.hardcode.dashboard.entity.AiInvestigationRun;
import ru.hardcode.dashboard.entity.AiInvestigationRunStatus;
import ru.hardcode.dashboard.entity.AiInvestigationUnavailableSource;

/**
 * Persistence of AI investigation runs and their stored facts and gaps. Completion and
 * failure updates are guarded by the {@code running} status, so a late or duplicate worker
 * result can never overwrite a terminal run.
 */
@Repository
public class AiInvestigationRunRepository {

	private final JdbcClient jdbc;

	public AiInvestigationRunRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Inserts one run with the facts and gaps gathered at launch. The caller owns the
	 * transaction: the run row and its children become visible together.
	 */
	public void create(AiInvestigationRun run, List<AiInvestigationFact> facts,
			List<AiInvestigationUnavailableSource> unavailable) {
		jdbc.sql("""
				INSERT INTO ai_investigation_run(run_id, incident_id, revision, status, window_from,
				    window_to, model_prompt, hypothesis, next_step, error_code, error_message,
				    created_at, finished_at)
				VALUES (:runId, :incidentId, :revision, :status, :windowFrom, :windowTo, :modelPrompt,
				    NULL, NULL, NULL, NULL, :createdAt, NULL)
				""")
				.param("runId", run.runId())
				.param("incidentId", run.incidentId())
				.param("revision", run.revision())
				.param("status", run.status().code())
				.param("windowFrom", Timestamp.from(run.windowFrom()))
				.param("windowTo", Timestamp.from(run.windowTo()))
				.param("modelPrompt", run.modelPrompt())
				.param("createdAt", Timestamp.from(run.createdAt()))
				.update();
		for (AiInvestigationFact fact : facts) {
			jdbc.sql("""
					INSERT INTO ai_investigation_fact(run_id, fact_index, evidence_id, source,
					    service_key, observed_at, message, message_truncated, level, assigned_command,
					    record_count, value, service_state)
					VALUES (:runId, :factIndex, :evidenceId, :source, :serviceKey, :observedAt,
					    :message, :messageTruncated, :level, :assignedCommand, :recordCount, :value,
					    :serviceState)
					""")
					.param("runId", fact.runId())
					.param("factIndex", fact.factIndex())
					.param("evidenceId", fact.evidenceId())
					.param("source", fact.source().name())
					.param("serviceKey", fact.serviceKey())
					.param("observedAt", Timestamp.from(fact.observedAt()))
					.param("message", fact.message())
					.param("messageTruncated", fact.messageTruncated())
					.param("level", fact.level())
					.param("assignedCommand", fact.assignedCommand())
					.param("recordCount", fact.recordCount())
					.param("value", fact.value())
					.param("serviceState", fact.serviceState())
					.update();
		}
		for (AiInvestigationUnavailableSource source : unavailable) {
			jdbc.sql("""
					INSERT INTO ai_investigation_unavailable_source(run_id, source_index, source,
					    service_key, code, reason)
					VALUES (:runId, :sourceIndex, :source, :serviceKey, :code, :reason)
					""")
					.param("runId", source.runId())
					.param("sourceIndex", source.sourceIndex())
					.param("source", source.source().name())
					.param("serviceKey", source.serviceKey())
					.param("code", source.code())
					.param("reason", source.reason())
					.update();
		}
	}

	public Optional<AiInvestigationRun> find(UUID runId) {
		return jdbc.sql("""
				SELECT run_id, incident_id, revision, status, window_from, window_to, model_prompt,
				       hypothesis, next_step, error_code, error_message, created_at, finished_at
				FROM ai_investigation_run WHERE run_id = :runId
				""").param("runId", runId).query(this::run).optional();
	}

	public List<AiInvestigationFact> facts(UUID runId) {
		return jdbc.sql("""
				SELECT run_id, fact_index, evidence_id, source, service_key, observed_at, message,
				       message_truncated, level, assigned_command, record_count, value, service_state
				FROM ai_investigation_fact WHERE run_id = :runId ORDER BY fact_index
				""").param("runId", runId).query(this::fact).list();
	}

	public List<AiInvestigationUnavailableSource> unavailable(UUID runId) {
		return jdbc.sql("""
				SELECT run_id, source_index, source, service_key, code, reason
				FROM ai_investigation_unavailable_source WHERE run_id = :runId ORDER BY source_index
				""").param("runId", runId).query(this::unavailableSource).list();
	}

	/** Stores the model answer; a run that is no longer running stays untouched. */
	public boolean complete(UUID runId, String hypothesis, String nextStep, Instant finishedAt) {
		return jdbc.sql("""
				UPDATE ai_investigation_run
				SET status = 'completed', hypothesis = :hypothesis, next_step = :nextStep,
				    error_code = NULL, error_message = NULL, finished_at = :finishedAt
				WHERE run_id = :runId AND status = 'running'
				""")
				.param("runId", runId)
				.param("hypothesis", hypothesis)
				.param("nextStep", nextStep)
				.param("finishedAt", Timestamp.from(finishedAt))
				.update() == 1;
	}

	/** Records the explicit public reason the run could not finish. */
	public boolean fail(UUID runId, String errorCode, String errorMessage, Instant finishedAt) {
		return jdbc.sql("""
				UPDATE ai_investigation_run
				SET status = 'failed', error_code = :errorCode, error_message = :errorMessage,
				    finished_at = :finishedAt
				WHERE run_id = :runId AND status = 'running'
				""")
				.param("runId", runId)
				.param("errorCode", errorCode)
				.param("errorMessage", errorMessage)
				.param("finishedAt", Timestamp.from(finishedAt))
				.update() == 1;
	}

	private AiInvestigationRun run(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
		return new AiInvestigationRun(
				rs.getObject("run_id", UUID.class),
				rs.getString("incident_id"),
				rs.getLong("revision"),
				AiInvestigationRunStatus.of(rs.getString("status")),
				instant(rs, "window_from"),
				instant(rs, "window_to"),
				rs.getString("model_prompt"),
				rs.getString("hypothesis"),
				rs.getString("next_step"),
				rs.getString("error_code"),
				rs.getString("error_message"),
				instant(rs, "created_at"),
				instant(rs, "finished_at"));
	}

	private AiInvestigationFact fact(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
		return new AiInvestigationFact(
				rs.getObject("run_id", UUID.class),
				rs.getInt("fact_index"),
				rs.getString("evidence_id"),
				McpSource.of(rs.getString("source")),
				rs.getString("service_key"),
				instant(rs, "observed_at"),
				rs.getString("message"),
				rs.getObject("message_truncated", Boolean.class),
				rs.getString("level"),
				rs.getString("assigned_command"),
				rs.getObject("record_count", Long.class),
				rs.getObject("value", Long.class),
				rs.getString("service_state"));
	}

	private AiInvestigationUnavailableSource unavailableSource(java.sql.ResultSet rs, int index)
			throws java.sql.SQLException {
		return new AiInvestigationUnavailableSource(
				rs.getObject("run_id", UUID.class),
				rs.getInt("source_index"),
				McpSource.of(rs.getString("source")),
				rs.getString("service_key"),
				rs.getString("code"),
				rs.getString("reason"));
	}

	private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	/** The three stand sources a fact or gap belongs to; {@code SCENARIO} is never stored. */
	private static final class McpSource {

		static ru.hardcode.dashboard.dto.McpEvidenceSource of(String name) {
			return ru.hardcode.dashboard.dto.McpEvidenceSource.valueOf(name);
		}
	}
}
