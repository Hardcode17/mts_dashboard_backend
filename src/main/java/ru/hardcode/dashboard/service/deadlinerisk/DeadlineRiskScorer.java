package ru.hardcode.dashboard.service.deadlinerisk;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * KAN-82 deadline risk scoring: a logistic-regression-shaped weighted sum of features read at
 * evaluation time, with category thresholds. The model is the configured constants — no training
 * and no ML runtime; real coefficients arrive from the offline KAN-81 notebook as a config edit.
 *
 * <p>score = w0 + w1*x1 + … + w7*x7, where
 * x1 — median resolution time of the resolution group divided by the hours left until the
 *      deadline (the main "similar tasks" feature),
 * x2 — deadline extensions observed so far,
 * x3 — group/assignee reassignments observed so far,
 * x4 — reopenings observed so far,
 * x5 — hours without a status change,
 * x6 — open incidents of the assignee,
 * x7 — the original ITSM priority (1–4).
 *
 * <p>The score is the raw weighted sum, never a probability. An incident without a deadline, a
 * resolution group or a usable group median is not scored: the result is
 * {@link DeadlineRiskLevel#UNKNOWN} with an explanatory reason, and the card keeps working. The
 * scorer only reads ITSM data; it never changes the deadline, priority or state, and it does not
 * replace the remaining-time warning of KAN-46.
 */
public class DeadlineRiskScorer {

	/** Floor for the deadline remainder so a passed deadline cannot divide by zero. */
	static final double MIN_REMAINING_HOURS = 0.05;

	private final DeadlineRiskProperties properties;

	public DeadlineRiskScorer(DeadlineRiskProperties properties) {
		this.properties = properties;
	}

	public DeadlineRiskResult evaluate(DeadlineRiskInput input) {
		if (input.deadline() == null) {
			return DeadlineRiskResult.unknown(List.of(new DeadlineRiskResult.Reason("no_deadline",
					"У инцидента нет дедлайна — риск не оценивается")));
		}
		if (input.assignedGroupName() == null) {
			return DeadlineRiskResult.unknown(List.of(new DeadlineRiskResult.Reason("no_history",
					"Карточка без назначенной группы — медиана решения недоступна")));
		}
		if (input.medianResolutionHours() == null || input.medianResolutionHours() <= 0) {
			return DeadlineRiskResult.unknown(List.of(new DeadlineRiskResult.Reason("no_history",
					("Нет завершённых карточек группы «%s» — медиана решения недоступна")
							.formatted(input.assignedGroupName()))));
		}

		double remaining = remainingHours(input.evaluatedAt(), input.deadline());
		double median = input.medianResolutionHours();
		double x1 = median / Math.max(remaining, MIN_REMAINING_HOURS);
		double x2 = Math.max(input.deadlineExtensions(), 0);
		double x3 = Math.max(input.reassignments(), 0);
		double x4 = Math.max(input.reopenings(), 0);
		double x5 = staleHours(input.timeSinceStatusChange());
		double x6 = Math.max(input.assigneeOpenIncidents(), 0);
		double x7 = input.priorityId();

		var weights = properties.weights();
		double score = weights.w0() + weights.w1() * x1 + weights.w2() * x2 + weights.w3() * x3
				+ weights.w4() * x4 + weights.w5() * x5 + weights.w6() * x6 + weights.w7() * x7;
		DeadlineRiskLevel risk = score >= properties.thresholds().high() ? DeadlineRiskLevel.HIGH
				: score >= properties.thresholds().medium() ? DeadlineRiskLevel.MEDIUM
						: DeadlineRiskLevel.LOW;
		return new DeadlineRiskResult(risk, score, reasons(input, median, remaining, x2, x3, x4, x5, x6));
	}

	private List<DeadlineRiskResult.Reason> reasons(DeadlineRiskInput input, double median,
			double remaining, double x2, double x3, double x4, double x5, double x6) {
		var reasons = new ArrayList<DeadlineRiskResult.Reason>();
		if (median > remaining) {
			reasons.add(new DeadlineRiskResult.Reason("group_median_exceeds",
					("Медиана группы решения «%s»: %s ч, до дедлайна %s ч").formatted(
							input.assignedGroupName(), hours(median), hours(Math.max(remaining, 0)))));
		}
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
		if (reasons.isEmpty()) {
			reasons.add(new DeadlineRiskResult.Reason("group_median",
					"Медиана группы решения «%s»: %s ч, до дедлайна %s ч".formatted(
							input.assignedGroupName(), hours(median), hours(Math.max(remaining, 0)))));
		}
		return reasons;
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
