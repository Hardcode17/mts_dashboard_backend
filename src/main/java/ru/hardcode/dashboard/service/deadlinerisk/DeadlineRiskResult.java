package ru.hardcode.dashboard.service.deadlinerisk;

import java.util.List;

/**
 * One scoring result (KAN-82): the category, the raw weighted sum for debugging and the
 * human-readable grounds. An {@link DeadlineRiskLevel#UNKNOWN} result carries no score.
 */
public record DeadlineRiskResult(DeadlineRiskLevel risk, Double score, List<Reason> reasons) {

	public DeadlineRiskResult {
		reasons = List.copyOf(reasons);
	}

	public static DeadlineRiskResult unknown(List<Reason> reasons) {
		return new DeadlineRiskResult(DeadlineRiskLevel.UNKNOWN, null, reasons);
	}

	/**
	 * One ground of the assessment with the concrete numbers behind it. Codes are stable API
	 * values; the texts are human-readable and may change with the weights.
	 */
	public record Reason(String code, String text) {
	}
}
