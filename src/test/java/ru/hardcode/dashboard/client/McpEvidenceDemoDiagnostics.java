package ru.hardcode.dashboard.client;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Stand diagnostics table for KAN-39 integration tests. It lives in the same Testcontainers
 * PostgreSQL 17 as the worker; the application reads it through its fixed diagnostic query,
 * and the test changes it only through the bounded helpers below.
 */
public final class McpEvidenceDemoDiagnostics {

	public static final String TABLE = "kan39_demo_service_diagnostics";
	public static final String READ_ONLY_USER = "kan39_evidence_reader";

	private static final String DDL = """
			CREATE TABLE IF NOT EXISTS %1$s (
			    record_id VARCHAR(64) PRIMARY KEY,
			    incident_id VARCHAR(64) NOT NULL,
			    scenario_run_id VARCHAR(128) NOT NULL,
				    service_key VARCHAR(128) NOT NULL,
			    recorded_at TIMESTAMPTZ NOT NULL,
			    value BIGINT NOT NULL
			)
			""".formatted(TABLE);

	/** The evidence connection may only read, which the isolation test proves. */
	private static final String READ_ONLY_GRANTS = """
			DO $$
			BEGIN
			    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '%1$s') THEN
			        CREATE ROLE %1$s LOGIN PASSWORD 'kan39-reader';
			    END IF;
			END
			$$;
			REVOKE ALL ON %2$s FROM %1$s;
			GRANT SELECT ON %2$s TO %1$s;
			""".formatted(READ_ONLY_USER, TABLE);

	private final PostgreSQLContainer postgres;

	public McpEvidenceDemoDiagnostics(PostgreSQLContainer postgres) {
		this.postgres = postgres;
	}

	/**
	 * Creates the stand table and the read-only evidence role in the already running
	 * container, then registers the read-only evidence connection for the application.
	 */
	public static void createStandSchema(PostgreSQLContainer postgres) {
		McpEvidenceDemoDiagnostics diagnostics = new McpEvidenceDemoDiagnostics(postgres);
		diagnostics.executeAsOwner(DDL);
		diagnostics.executeAsOwner(READ_ONLY_GRANTS);
	}

	public void reset() {
		executeAsOwner("TRUNCATE TABLE " + TABLE);
	}

	public void insert(String recordId, String incidentId, String serviceKey, Instant recordedAt, long value) {
		insert(recordId, incidentId, ru.hardcode.dashboard.McpEvidenceSupport.RUN, serviceKey, recordedAt, value);
	}

	public void insert(String recordId, String incidentId, String run, String serviceKey, Instant recordedAt, long value) {
		executeAsOwner("INSERT INTO " + TABLE + " (record_id, incident_id, scenario_run_id, service_key, recorded_at, value)"
				+ " VALUES (?, ?, ?, ?, ?, ?)", statement -> {
			statement.setString(1, recordId);
			statement.setString(2, incidentId);
			statement.setString(3, run);
			statement.setString(4, serviceKey);
			statement.setObject(5, recordedAt.atOffset(java.time.ZoneOffset.UTC));
			statement.setLong(6, value);
		});
	}

	/** Changes the stand record in place, as an operator would on the stand. */
	public void updateValue(String recordId, long value) {
		executeAsOwner("UPDATE " + TABLE + " SET value = ? WHERE record_id = ?", statement -> {
			statement.setLong(1, value);
			statement.setString(2, recordId);
		});
	}

	public void updateRecordedAt(String recordId, Instant recordedAt) {
		executeAsOwner("UPDATE " + TABLE + " SET recorded_at = ? WHERE record_id = ?", statement -> {
			statement.setObject(1, recordedAt.atOffset(java.time.ZoneOffset.UTC));
			statement.setString(2, recordId);
		});
	}

	public long count() {
		try (Connection connection = connect(postgres.getUsername(), postgres.getPassword());
				Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery("SELECT count(*) FROM " + TABLE)) {
			resultSet.next();
			return resultSet.getLong(1);
		} catch (SQLException exception) {
			throw new IllegalStateException("Cannot read demo diagnostics", exception);
		}
	}

	/** Proves the evidence connection cannot write to the stand table. */
	public boolean readOnlyConnectionCannotWrite() {
		try (Connection connection = connect(READ_ONLY_USER, "kan39-reader")) {
			try (PreparedStatement statement = connection.prepareStatement(
					"UPDATE " + TABLE + " SET value = value + 1")) {
				statement.executeUpdate();
				return false;
			}
		} catch (SQLException exception) {
			return true;
		}
	}

	private void executeAsOwner(String sql) {
		executeAsOwner(sql, statement -> {
		});
	}

	private void executeAsOwner(String sql, StatementBinder binder) {
		try (Connection connection = connect(postgres.getUsername(), postgres.getPassword());
				PreparedStatement statement = connection.prepareStatement(sql)) {
			binder.bind(statement);
			statement.execute();
		} catch (SQLException exception) {
			throw new IllegalStateException("Cannot run a demo diagnostics fixture statement", exception);
		}
	}

	private Connection connect(String username, String password) throws SQLException {
		return DriverManager.getConnection(postgres.getJdbcUrl(), username, password);
	}

	@FunctionalInterface
	private interface StatementBinder {
		void bind(PreparedStatement statement) throws SQLException;
	}
}
