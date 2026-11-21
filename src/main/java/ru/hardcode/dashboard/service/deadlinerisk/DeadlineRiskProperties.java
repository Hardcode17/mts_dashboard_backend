package ru.hardcode.dashboard.service.deadlinerisk;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * KAN-82 model constants ({@code deadline-risk.*}). The model is pure configuration: trained
 * offline (KAN-81, UCI ServiceNow) and replaced here as one config edit — the application code
 * never changes between trainings.
 *
 * <p>Product decision: the feature-aware {@link Mode#REGRESSION} is the default — the team
 * deliberately prefers the smart forecast (reassignments, stuck cards, assignee load, group
 * history) even though the plain remaining-time threshold is marginally sharper on the UCI
 * test (F1 0.778 vs 0.769). The weights are retrained on real MTS history by the KAN-81 script.
 * {@link Mode#THRESHOLD} stays the simple fallback mode.
 *
 * @param mode           which scoring rule is active
 * @param thresholdHours fallback mode: high when less than this many hours remain until the
 *                       deadline; calibrated on UCI ServiceNow (N = 32.92) and to be recomputed
 *                       on real MTS history
 * @param bufferHours    fallback mode: medium inside the (threshold, buffer) zone, low above it;
 *                       2*N by default
 * @param regression     weights, thresholds and feature clamps of the default mode
 * @param reasons        when a feature becomes a named ground of the assessment
 */
@ConfigurationProperties(prefix = "deadline-risk")
public record DeadlineRiskProperties(
		Mode mode,
		Double thresholdHours,
		Double bufferHours,
		Regression regression,
		Reasons reasons
) {

	/** Fail fast on a half-configured model instead of scoring silently with wrong constants. */
	public DeadlineRiskProperties {
		if (mode == null) {
			mode = Mode.REGRESSION;
		}
		if (thresholdHours == null) {
			thresholdHours = 32.92;
		}
		if (bufferHours == null) {
			bufferHours = 2 * thresholdHours;
		}
		if (regression == null) {
			regression = new Regression(null, null, null, 0.25, null);
		}
		if (reasons == null) {
			reasons = new Reasons(24.0, 5L);
		}
		if (!Double.isFinite(thresholdHours) || thresholdHours <= 0) {
			throw new IllegalArgumentException(
					"deadline-risk.threshold-hours must be a positive finite number of hours");
		}
		if (!Double.isFinite(bufferHours) || bufferHours <= thresholdHours) {
			throw new IllegalArgumentException(
					"deadline-risk.buffer-hours must be a finite number of hours above deadline-risk.threshold-hours");
		}
	}

	/** The active scoring rule; the default is the feature-aware regression (product decision). */
	public enum Mode {
		THRESHOLD,
		REGRESSION
	}

	/**
	 * KAN-81 logistic regression without standardization. score = w0 + w1*x1 + … + w7*x7 with the
	 * feature clamps fixed by the offline notebook; category boundaries on the raw score are
	 * inclusive ({@code medium} first, then {@code high}).
	 */
	public record Regression(Weights weights, Thresholds thresholds, Clamps clamps, double minRemainingHours,
			List<Double> priorityMediansHours) {
		public Regression {
			if (weights == null) {
				weights = new Weights(4.379061, 0.010472, 0.167612, -0.030004, 0.42397,
						0.033348, 0.007605, -2.089877);
			}
			if (thresholds == null) {
				thresholds = new Thresholds(-1.286237, -0.913755);
			}
			if (clamps == null) {
				clamps = new Clamps(new Clamp(0, 100), new Clamp(0, 200), new Clamp(0, 50),
						new Clamp(0, 20), new Clamp(0, 2160), new Clamp(0, 100), new Clamp(1, 4));
			}
			priorityMediansHours = priorityMediansHours == null ? List.of(52.8917, 67.7833, 27.0667, 30.4)
					: List.copyOf(priorityMediansHours);
			if (priorityMediansHours.size() != 4 || priorityMediansHours.stream().anyMatch(h -> !Double.isFinite(h) || h <= 0)) {
				throw new IllegalArgumentException("deadline-risk.regression.priority-medians-hours requires four positive finite hours");
			}
			if (!Double.isFinite(minRemainingHours) || minRemainingHours <= 0) {
				throw new IllegalArgumentException(
						"deadline-risk.regression.min-remaining-hours must be a positive finite number of hours");
			}
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
				throw new IllegalArgumentException("deadline-risk.regression.weights.* must be finite numbers");
			}
		}
	}

	public record Thresholds(double medium, double high) {
		public Thresholds {
			if (!Double.isFinite(medium) || !Double.isFinite(high) || !(medium < high)) {
				throw new IllegalArgumentException(
						"deadline-risk.regression.thresholds.medium must be a finite number below"
								+ " deadline-risk.regression.thresholds.high");
			}
		}
	}

	/** Feature bounds fixed by the offline notebook; the scorer clamps every feature to them. */
	public record Clamps(Clamp x1, Clamp x2, Clamp x3, Clamp x4, Clamp x5, Clamp x6, Clamp x7) {
		public Clamps {
			if (x1 == null || x2 == null || x3 == null || x4 == null || x5 == null || x6 == null || x7 == null) {
				throw new IllegalArgumentException("deadline-risk.regression.clamps.* must configure x1..x7");
			}
		}
	}

	public record Clamp(double min, double max) {
		public Clamp {
			if (!Double.isFinite(min) || !Double.isFinite(max) || !(min < max)) {
				throw new IllegalArgumentException(
						"deadline-risk.regression.clamps bounds must be finite numbers with min below max");
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
