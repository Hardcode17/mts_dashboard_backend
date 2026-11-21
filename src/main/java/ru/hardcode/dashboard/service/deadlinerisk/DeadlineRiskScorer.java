package ru.hardcode.dashboard.service.deadlinerisk;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * KAN-82 deadline risk scoring, driven entirely by the configured constants — no training and
 * no ML runtime. Real numbers arrive from the offline KAN-81 notebook as a config edit.
 *
 * <p>Two modes, selected by {@code deadline-risk.mode}. The product default is
 * {@code regression} (the team's deliberate choice of the feature-aware forecast; its weights,
 * category thresholds and feature clamps reproduce {@code tools/deadline_risk_weights.json}
 * without standardization, and it is retrained on real MTS history by the KAN-81 script):
 * <ul>
 * <li>{@code regression}: score = w0 + w1*x1 + … + w7*x7 with category thresholds. x1 — median
 * resolution time of the resolution group divided by the hours left, x2 — deadline extensions,
 * x3 — reassignments, x4 — reopenings, x5 — hours without a status change, x6 — assignee's
 * open incidents, x7 — the ITSM priority; every feature is clamped to the bounds fixed
 * offline. The score is the raw weighted sum, never a probability. Without a usable group
 * median the main feature is missing and the assessment yields {@code unknown} rather than
 * silently degrading.</li>
 * <li>{@code threshold} (the fallback): the KAN-81 winning baseline — risk is high when less
 * than {@code deadline-risk.threshold-hours} remain until the deadline, medium inside the
 * buffer zone, low above it. The rule needs only the deadline, so a missing group median never
 * blocks the assessment.</li>
 * </ul>
 *
 * <p>An incident without a deadline is never scored: the result is
 * {@link DeadlineRiskLevel#UNKNOWN} with an explanatory reason, and the card keeps working. In
 * the regression mode a missing group median also yields {@code unknown}: the main feature would
 * be unavailable. The scorer only reads ITSM data; it never changes the deadline, priority or
 * state, and it does not replace the remaining-time warning of KAN-46.
 */
public class DeadlineRiskScorer {

	private final DeadlineRiskProperties properties;

	public DeadlineRiskScorer(DeadlineRiskProperties properties) {
		this.properties = properties;
	}

	public DeadlineRiskResult evaluate(DeadlineRiskInput input) {
		if (input.deadline() == null) {
			return DeadlineRiskResult.unknown(List.of(new DeadlineRiskResult.Reason("no_deadline",
					"У инцидента нет дедлайна — риск не оценивается")));
		}
		double remaining = remainingHours(input.evaluatedAt(), input.deadline());
		return properties.mode() == DeadlineRiskProperties.Mode.REGRESSION
				? evaluateRegression(input, remaining)
				: evaluateThreshold(input, remaining);
	}

	/**
	 * The fallback rule, marginally sharper on the UCI test (F1 0.778 vs 0.769): the remaining
	 * time itself is the strongest signal there. The median comparison is added as an extra
	 * ground whenever the group history exists — the badge must explain why the risk is high,
	 * unlike the plain KAN-46 countdown.
	 */
	private DeadlineRiskResult evaluateThreshold(DeadlineRiskInput input, double remaining) {
		var reasons = new ArrayList<DeadlineRiskResult.Reason>();
		DeadlineRiskLevel risk;
		if (remaining < properties.thresholdHours()) {
			risk = DeadlineRiskLevel.HIGH;
			reasons.add(new DeadlineRiskResult.Reason("remaining_below_threshold",
					"До дедлайна %s ч, порог %s ч".formatted(hours(remaining), hours(properties.thresholdHours()))));
		} else if (remaining < properties.bufferHours()) {
			risk = DeadlineRiskLevel.MEDIUM;
			reasons.add(new DeadlineRiskResult.Reason("remaining_in_buffer",
					"До дедлайна %s ч, буфер %s ч".formatted(hours(remaining), hours(properties.bufferHours()))));
		} else {
			risk = DeadlineRiskLevel.LOW;
		}
		addMedianGround(input, remaining, reasons);
		return new DeadlineRiskResult(risk, null, reasons);
	}

	/**
	 * KAN-81 logistic regression without standardization — the product default: the team
	 * deliberately prefers the feature-aware forecast; every feature is clamped to the offline
	 * bounds before the weighted sum. Without a usable group median the main feature is missing
	 * and the assessment is {@code unknown} rather than silently degraded.
	 */
	private DeadlineRiskResult evaluateRegression(DeadlineRiskInput input, double remaining) {
		if (input.assignedGroupName() == null) {
			return DeadlineRiskResult.unknown(List.of(new DeadlineRiskResult.Reason("no_history",
					"Карточка без назначенной группы — медиана решения недоступна")));
		}
		if (input.medianResolutionHours() == null || input.medianResolutionHours() <= 0) {
			return DeadlineRiskResult.unknown(List.of(new DeadlineRiskResult.Reason("no_history",
					("Нет завершённых карточек группы «%s» — медиана решения недоступна")
							.formatted(input.assignedGroupName()))));
		}
		var regression = properties.regression();
		var clamps = regression.clamps();
		double x1 = clamp(input.medianResolutionHours()
				/ Math.max(remaining, regression.minRemainingHours()), clamps.x1());
		double x2 = clamp(Math.max(input.deadlineExtensions(), 0), clamps.x2());
		double x3 = clamp(Math.max(input.reassignments(), 0), clamps.x3());
		double x4 = clamp(Math.max(input.reopenings(), 0), clamps.x4());
		double x5 = clamp(staleHours(input.timeSinceStatusChange()), clamps.x5());
		double x6 = clamp(Math.max(input.assigneeOpenIncidents(), 0), clamps.x6());
		double x7 = clamp(input.priorityId(), clamps.x7());

		var weights = regression.weights();
		double score = weights.w0() + weights.w1() * x1 + weights.w2() * x2 + weights.w3() * x3
				+ weights.w4() * x4 + weights.w5() * x5 + weights.w6() * x6 + weights.w7() * x7;
		DeadlineRiskLevel risk = score >= regression.thresholds().high() ? DeadlineRiskLevel.HIGH
				: score >= regression.thresholds().medium() ? DeadlineRiskLevel.MEDIUM
						: DeadlineRiskLevel.LOW;

		var reasons = new ArrayList<DeadlineRiskResult.Reason>();
		addMedianGround(input, remaining, reasons);
		if (x2 >= 1) {
			reasons.add(new DeadlineRiskResult.Reason("deadline_extended",
					"Дедлайн уже продлевался %d %s".formatted((long) x2, times((long) x2))));
		}
		if (x3 >= 1) {
			reasons.add(new DeadlineRiskResult.Reason("reassigned",
					"Группа или исполнитель менялись %d %s".formatted((long) x3, times((long) x3))));
		}
		if (x4 >= 1) {
			reasons.add(new DeadlineRiskResult.Reason("reopened",
					"Карточка переоткрывалась %d %s".formatted((long) x4, times((long) x4))));
		}
		if (x5 >= properties.reasons().staleStatusHours()) {
			reasons.add(new DeadlineRiskResult.Reason("status_stale",
					"Без смены статуса %s ч".formatted(hours(x5))));
		}
		if (x6 >= properties.reasons().assigneeOpenThreshold()) {
			reasons.add(new DeadlineRiskResult.Reason("assignee_overloaded",
					"Открытых карточек у исполнителя: %d".formatted((long) x6)));
		}
		return new DeadlineRiskResult(risk, score, reasons);
	}

	/** The group-median ground of both modes: similar cards took longer than the time left. */
	private static void addMedianGround(DeadlineRiskInput input, double remaining,
			List<DeadlineRiskResult.Reason> reasons) {
		if (input.assignedGroupName() != null && input.medianResolutionHours() != null
				&& input.medianResolutionHours() > remaining) {
			reasons.add(new DeadlineRiskResult.Reason("group_median_exceeds",
					("Медиана группы решения «%s»: %s ч, остаток %s ч").formatted(input.assignedGroupName(),
							hours(input.medianResolutionHours()), hours(Math.max(remaining, 0)))));
		}
	}

	private static double remainingHours(Instant evaluatedAt, Instant deadline) {
		return Duration.between(evaluatedAt, deadline).toMillis() / 3_600_000.0;
	}

	private static double staleHours(Duration timeSinceStatusChange) {
		if (timeSinceStatusChange == null || timeSinceStatusChange.isNegative()) {
			return 0;
		}
		return timeSinceStatusChange.toMillis() / 3_600_000.0;
	}

	private static double clamp(double value, DeadlineRiskProperties.Clamp bounds) {
		return Math.min(Math.max(value, bounds.min()), bounds.max());
	}

	private static String hours(double value) {
		return String.format(Locale.ROOT, "%.1f", value);
	}

	/** Russian plural of «раз»: 1 раз, 2 раза, 5 раз. */
	private static String times(long count) {
		if (count % 10 == 1 && count % 100 != 11) {
			return "раз";
		}
		if (count % 10 >= 2 && count % 10 <= 4 && (count % 100 < 12 || count % 100 > 14)) {
			return "раза";
		}
		return "раз";
	}
}
