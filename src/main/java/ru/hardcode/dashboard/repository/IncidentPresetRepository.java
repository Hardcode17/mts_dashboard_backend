package ru.hardcode.dashboard.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import ru.hardcode.dashboard.dto.IncidentPresetRequest;
import ru.hardcode.dashboard.dto.IncidentPresetResponse;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class IncidentPresetRepository {

	private static final TypeReference<Map<String, String>> FILTERS = new TypeReference<>() {};

	private final JdbcTemplate jdbc;
	private final ObjectMapper mapper;
	private final RowMapper<IncidentPresetResponse> rowMapper;

	public IncidentPresetRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.mapper = mapper;
		this.rowMapper = (row, index) -> new IncidentPresetResponse(row.getLong("id"), row.getString("name"),
				row.getString("queue"), mapper.readValue(row.getString("filters"), FILTERS));
	}

	public List<IncidentPresetResponse> list(long owner) {
		return jdbc.query("""
				SELECT id, name, queue, filters FROM coordinator_incident_presets
				WHERE owner_user_id = ? ORDER BY id
				""", rowMapper, owner);
	}

	public Optional<IncidentPresetResponse> find(long owner, long id) {
		return jdbc.query("""
				SELECT id, name, queue, filters FROM coordinator_incident_presets
				WHERE owner_user_id = ? AND id = ?
				""", rowMapper, owner, id).stream().findFirst();
	}

	public IncidentPresetResponse create(long owner, IncidentPresetRequest request) {
		return jdbc.queryForObject("""
				INSERT INTO coordinator_incident_presets (owner_user_id, name, queue, filters)
				VALUES (?, ?, ?, CAST(? AS jsonb))
				RETURNING id, name, queue, filters
				""", rowMapper, owner, request.name(), request.queue(), mapper.writeValueAsString(request.filters()));
	}

	public Optional<IncidentPresetResponse> update(long owner, long id, IncidentPresetRequest request) {
		return jdbc.query("""
				UPDATE coordinator_incident_presets SET name = ?, queue = ?, filters = CAST(? AS jsonb)
				WHERE owner_user_id = ? AND id = ?
				RETURNING id, name, queue, filters
				""", rowMapper, request.name(), request.queue(), mapper.writeValueAsString(request.filters()), owner, id)
				.stream().findFirst();
	}

	public boolean delete(long owner, long id) {
		return jdbc.update("DELETE FROM coordinator_incident_presets WHERE owner_user_id = ? AND id = ?",
				owner, id) > 0;
	}
}
