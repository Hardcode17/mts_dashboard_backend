package ru.hardcode.dashboard.service.evidence;

import ru.hardcode.dashboard.configuration.evidence.ConditionalOnEvidenceMapping;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Properties;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import ru.hardcode.dashboard.configuration.evidence.McpEvidenceDemoProperties;
import ru.hardcode.dashboard.dto.McpEvidenceSource;

/**
 * Diagnostic metric from the controlled stand diagnostics database. Every identifier in the
 * fixed SQL text is validated against a strict pattern first, so neither the incident id nor
 * the configured source can carry SQL. The connection is opened per read, read-only,
 * auto-commit, with a statement timeout and a transaction read-only hint; only the two fixed
 * selects below ever run.
 */
@Component
@ConditionalOnEvidenceMapping
public class DatabaseDiagnosticEvidenceSource implements McpEvidenceSourcePort {

	static final String COUNT_SQL_TEMPLATE = "SELECT count(*) FROM %s "
			+ "WHERE incident_id = ? AND scenario_run_id = ? AND service_key = ? AND recorded_at >= ? AND recorded_at <= ?";
	static final String LATEST_SQL_TEMPLATE = "SELECT record_id, recorded_at, value, service_key FROM %s "
			+ "WHERE incident_id = ? AND scenario_run_id = ? AND service_key = ? AND recorded_at >= ? AND recorded_at <= ? "
			+ "ORDER BY recorded_at DESC, record_id DESC LIMIT 1";
	static final int QUERY_TIMEOUT_SECONDS = 3;
	static final int CONNECT_TIMEOUT_SECONDS = 2;

	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]{0,127}");
	private static final Pattern INCIDENT_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
	private static final Pattern SERVICE_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

	private final McpEvidenceDemoProperties properties;

	public DatabaseDiagnosticEvidenceSource(McpEvidenceDemoProperties properties) {
		this.properties = properties;
	}

	@Override
	public McpEvidenceSource source() {
		return McpEvidenceSource.DATABASE;
	}

	@Override
	public SourceReading read(EvidenceQuery query) {
		McpEvidenceDemoProperties.DatabaseSource source = properties.database();
		if (source == null || source.url() == null || source.url().isBlank()
				|| source.view() == null || !IDENTIFIER.matcher(source.view()).matches()
				|| source.username() == null) {
			throw McpEvidenceSourceException.unavailable(source(), query.serviceKey(), null);
		}
		if (!INCIDENT_ID.matcher(query.incidentId()).matches()
				|| !SERVICE_KEY.matcher(query.serviceKey()).matches()) {
			throw McpEvidenceSourceException.rejected(source(), query.serviceKey());
		}

		Timestamp from = Timestamp.from(query.from());
		Timestamp to = Timestamp.from(query.to());
		String countSql = COUNT_SQL_TEMPLATE.formatted(source.view());
		String latestSql = LATEST_SQL_TEMPLATE.formatted(source.view());
		try (Connection connection = connect(source)) {
			long count;
			try (PreparedStatement statement = connection.prepareStatement(countSql)) {
				statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
				bind(statement, query, from, to);
				try (ResultSet resultSet = statement.executeQuery()) {
					if (!resultSet.next()) {
						throw McpEvidenceSourceException.rejected(source(), query.serviceKey());
					}
					count = resultSet.getLong(1);
				}
			}
			try (PreparedStatement statement = connection.prepareStatement(latestSql)) {
				statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
				bind(statement, query, from, to);
				try (ResultSet resultSet = statement.executeQuery()) {
					if (!resultSet.next()) {
						throw McpEvidenceSourceException.unavailable(source(), query.serviceKey(), null);
					}
					if (!query.serviceKey().equals(resultSet.getString("service_key"))) {
						// The row must be the record of the explicitly bound service.
						throw McpEvidenceSourceException.notAttributed(
								source(), query.serviceKey(), query.incidentId(), query.scenarioRunId());
					}
					return new SourceReading(
							"diag:" + resultSet.getString("record_id"),
							instant(resultSet, "recorded_at"),
							query.incidentId(),
							query.scenarioRunId(),
							new Observation(
									null,
									null,
									null,
									null,
									count,
									resultSet.getLong("value"),
									null
							)
					);
				}
			}
		} catch (SQLTimeoutException exception) {
			throw McpEvidenceSourceException.unavailable(source(), query.serviceKey(), exception);
		} catch (SQLException exception) {
			throw McpEvidenceSourceException.unavailable(source(), query.serviceKey(), exception);
		}
	}

	private static void bind(PreparedStatement statement, EvidenceQuery query, Timestamp from, Timestamp to)
			throws SQLException {
		statement.setString(1, query.incidentId());
		statement.setString(2, query.scenarioRunId());
		statement.setString(3, query.serviceKey());
		statement.setTimestamp(4, from);
		statement.setTimestamp(5, to);
	}

	private static Connection connect(McpEvidenceDemoProperties.DatabaseSource source) throws SQLException {
		// Keep every timestamp the database returns in UTC exactly as stored; the JDBC driver
		// must not convert it to the JVM or database default zone.
		Properties properties = new Properties();
		properties.setProperty("user", source.username());
		properties.setProperty("connectTimeout", Integer.toString(CONNECT_TIMEOUT_SECONDS));
		properties.setProperty("readOnly", "true");
		properties.setProperty("readOnlyMode", "always");
		properties.setProperty("options", "-c TimeZone=UTC");
		properties.setProperty("password", source.password() == null ? "" : source.password());
		return DriverManager.getConnection(source.url(), properties);
	}

	private static Instant instant(ResultSet resultSet, String column) throws SQLException {
		OffsetDateTime timestamp = resultSet.getObject(column, OffsetDateTime.class);
		return timestamp == null ? null : timestamp.toInstant();
	}
}
