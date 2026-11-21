package ru.hardcode.dashboard.service.deadlinerisk;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * KAN-82 model constants ({@code deadline-risk.*}). The weights and category thresholds are the
 * whole model: they are trained offline (KAN-81, UCI ServiceNow) and land here as one config
 * edit — the application code never changes between trainings. The shipped values are
 * placeholders, not learned coefficients.
 *
 * @param weights    w0..w7 of score = w0 + w1*x1 + … + w7*x7
 * @param thresholds category boundaries on the raw score: {@code medium} is inclusive,
 *                   {@code high} is inclusive and must stay above {@code medium}
 * @param reasons    when a feature becomes a named ground of the assessment
 */
@ConfigurationProperties(prefix = "deadline-risk")
public record DeadlineRiskProperties(Weights weights, Thresholds thresholds, Reasons reasons) {

	/** Fail fast on a half-configured model instead of scoring silently with defaults. */
	public DeadlineRiskProperties {
		if (weights == null) {
			weights = new Weights(-1.0, 1.0, 1.0, 0.5, 1.0, 0.05, 0.1, 0.25);
		}
		if (thresholds == null) {
			thresholds = new Thresholds(0.0, 1.0);
		}
		if (reasons == null) {
			reasons = new Reasons(24.0, 5L);
		}
	}

	public record Weights(
			double w0,
			double w1,
			double w2,
			double w3,
			double w4,
			double w5,
			double w6,
			double w7
	) {
		public Weights {
			if (!Double.isFinite(w0) || !Double.isFinite(w1) || !Double.isFinite(w2)
					|| !Double.isFinite(w3) || !Double.isFinite(w4) || !Double.isFinite(w5)
					|| !Double.isFinite(w6) || !Double.isFinite(w7)) {
				throw new IllegalArgumentException("deadline-risk.weights.* must be finite numbers");
			}
		}
	}

	public record Thresholds(double medium, double high) {
		public Thresholds {
			if (!Double.isFinite(medium) || !Double.isFinite(high) || !(medium < high)) {
				throw new IllegalArgumentException(
						"deadline-risk.thresholds.medium must be a finite number below deadline-risk.thresholds.high");
			}
		}
	}

	/** Non-scoring tuning: which feature values are worth showing as grounds. */
	public record Reasons(double staleStatusHours, long assigneeOpenThreshold) {
		public Reasons {
			if (!Double.isFinite(staleStatusHours) || staleStatusHours <= 0) {
				throw new IllegalArgumentException(
						"deadline-risk.reasons.stale-status-hours must be a positive finite number of hours");
			}
			if (assigneeOpenThreshold < 1) {
				throw new IllegalArgumentException(
						"deadline-risk.reasons.assignee-open-threshold must be at least 1");
			}
		}
	}
}
