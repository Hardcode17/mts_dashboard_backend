package ru.hardcode.dashboard.repository;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.hardcode.dashboard.entity.CoordinatorUser;

@Repository
public class CoordinatorUserRepository {

	private final JdbcTemplate jdbcTemplate;

	public CoordinatorUserRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public Optional<CoordinatorUser> findByIdentity(String issuer, String subject) {
		return jdbcTemplate.query("""
				SELECT id, identity_issuer, identity_subject, login, display_name,
				       is_active, created_at, updated_at
				FROM users WHERE identity_issuer = ? AND identity_subject = ?
				""", (row, index) -> new CoordinatorUser(
				row.getLong("id"), row.getString("identity_issuer"), row.getString("identity_subject"),
				row.getString("login"), row.getString("display_name"), row.getBoolean("is_active"),
				row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant()
		), issuer, subject).stream().findFirst();
	}
}
