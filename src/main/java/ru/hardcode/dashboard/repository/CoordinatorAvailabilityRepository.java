package ru.hardcode.dashboard.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.hardcode.dashboard.dto.CoordinatorAvailabilityResponse.Group;

@Repository
public class CoordinatorAvailabilityRepository {

	private final JdbcTemplate jdbc;

	public CoordinatorAvailabilityRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<Group> activeGroups(long userId) {
		return jdbc.query("""
				SELECT g.id, g.name
				FROM coordinator_group_members m
				JOIN coordinator_groups g ON g.id = m.coordinator_group_id
				WHERE m.user_id = ? AND m.is_active AND g.is_active
				ORDER BY g.name, g.id
				""", (row, index) -> new Group(row.getLong("id"), row.getString("name")), userId);
	}

	public boolean lockActiveAccount(long userId) {
		return !jdbc.queryForList("SELECT id FROM users WHERE id = ? AND is_active FOR UPDATE", Long.class, userId).isEmpty();
	}

	public Optional<Availability> find(long userId) {
		return jdbc.query("""
				SELECT available, available_since, generation
				FROM coordinator_availability WHERE user_id = ?
				""", (row, index) -> {
			OffsetDateTime since = row.getObject("available_since", OffsetDateTime.class);
			return new Availability(row.getBoolean("available"), since == null ? null : since.toInstant(),
					row.getLong("generation"));
		}, userId).stream().findFirst();
	}

	public void set(long userId, boolean available, Instant now) {
		jdbc.update("""
				INSERT INTO coordinator_availability (user_id, available, available_since, generation)
				VALUES (?, ?, ?, ?)
				ON CONFLICT (user_id) DO UPDATE SET
				    available = excluded.available,
				    available_since = coalesce(excluded.available_since, coordinator_availability.available_since),
				    generation = coordinator_availability.generation + excluded.generation
				WHERE coordinator_availability.available <> excluded.available
				""", userId, available, available ? now.atOffset(ZoneOffset.UTC) : null, available ? 1 : 0);
	}

	public record Availability(boolean available, Instant availableSince, long generation) {
	}
}
