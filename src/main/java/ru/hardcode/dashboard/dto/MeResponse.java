package ru.hardcode.dashboard.dto;

import java.util.List;

/**
 * Human identity taken from a validated access token. {@code subject} is the token {@code sub}.
 */
public record MeResponse(
		String subject,
		String username,
		String name,
		String email,
		List<String> audience
) {
}
