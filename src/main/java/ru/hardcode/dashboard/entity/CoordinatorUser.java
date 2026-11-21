package ru.hardcode.dashboard.entity;

import java.time.Instant;

/** Local access profile; never a password store or an external ITSM employee. */
public record CoordinatorUser(
		long id, String identityIssuer, String identitySubject, String login,
		String displayName, boolean active, Instant createdAt, Instant updatedAt
) {
}
