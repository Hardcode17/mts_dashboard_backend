package ru.hardcode.dashboard.dto;

import java.time.Instant;
import java.util.List;

/**
 * KAN-82 deadline risk of one incident card. {@code risk} is one of {@code low | medium | high |
 * unknown}; {@code unknown} never blocks the card. {@code reasons} are the human-readable grounds
 * with the concrete numbers behind the category. {@code score} is the raw weighted sum of the
 * features — debugging only, never a probability and not for the UI.
 */
public record DeadlineRiskResponse(String risk, Instant evaluatedAt, List<Reason> reasons, Double score) {

	public DeadlineRiskResponse {
		reasons = List.copyOf(reasons);
	}

	public record Reason(String code, String text) {
	}
}
