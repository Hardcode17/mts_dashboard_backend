package ru.hardcode.dashboard.service.deadlinerisk;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class DeadlineRiskScorerTest {

	private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");
	private static final DeadlineRiskProperties PROPERTIES = new DeadlineRiskProperties(null, null, null);

	private final DeadlineRiskScorer scorer = new DeadlineRiskScorer(PROPERTIES);

	@Test
	void sumsTheWeightedFeaturesAndCategorizesByConfiguredThresholds() {
		// x1 = 6/2 = 3, x2 = 1, x3 = 2, x4 = 1, x5 = 30, x6 = 7, x7 = 4:
		// score = -1 + 3 + 1 + 2*0.5 + 1 + 30*0.05 + 7*0.1 + 4*0.25 = 7.2 >= high(1.0).
		var result = scorer.evaluate(new DeadlineRiskInput("Support",
				Instant.parse("2026-11-21T12:00:00Z"), NOW, 6.0, 1, 2, 1,
				Duration.ofHours(30), 7, 4));

		assertThat(result.risk()).isEqualTo(DeadlineRiskLevel.HIGH);
		assertThat(result.score()).isCloseTo(7.2, offset(1e-9));
		assertThat(result.reasons()).extracting(DeadlineRiskResult.Reason::code).containsExactly(
				"group_median_exceeds", "deadline_extended", "reassigned", "reopened",
				"status_stale", "assignee_overloaded");
	}

	@Test
	void scoreAtOrAboveHighThresholdIsHighAndAtOrAboveMediumIsMedium() {
		// x1 = 3/1 = 3, priority 1: score = -1 + 3 + 0.25 = 2.25.
		assertThat(scorer.evaluate(input(1.0, 3.0, 0, 1)).risk()).isEqualTo(DeadlineRiskLevel.HIGH);
		// Remaining 9 h, median 6 h: x1 = 6/9, score = -1 + 0.667 + 0.25 = -0.083 -> low.
		assertThat(scorer.evaluate(input(9.0, 6.0, 0, 1)).risk()).isEqualTo(DeadlineRiskLevel.LOW);
		// Remaining 5 h: x1 = 6/5 = 1.2, score = -1 + 1.2 + 0.25 = 0.45 -> medium.
		assertThat(scorer.evaluate(input(5.0, 6.0, 0, 1)).risk()).isEqualTo(DeadlineRiskLevel.MEDIUM);
	}

	@Test
	void thresholdsAreInclusiveOnBothBoundaries() {
		// score exactly at the high threshold stays high: x1 = 7/4, score = -1 + 1.75 + 0.25 = 1.0.
		var high = scorer.evaluate(input(4.0, 7.0, 0, 1));
		assertThat(high.risk()).isEqualTo(DeadlineRiskLevel.HIGH);
		assertThat(high.score()).isEqualTo(1.0);
		// score exactly at the medium threshold stays medium: x1 = 3/4, score = -1 + 0.75 + 0.25 = 0.0.
		var medium = scorer.evaluate(input(4.0, 3.0, 0, 1));
		assertThat(medium.risk()).isEqualTo(DeadlineRiskLevel.MEDIUM);
		assertThat(medium.score()).isEqualTo(0.0);
	}

	@Test
	void incidentWithoutDeadlineIsUnknownWithoutScoring() {
		var result = scorer.evaluate(new DeadlineRiskInput("Support", null, NOW, 6.0, 1, 0, 0,
				Duration.ofHours(1), 0, 4));

		assertThat(result.risk()).isEqualTo(DeadlineRiskLevel.UNKNOWN);
		assertThat(result.score()).isNull();
		assertThat(result.reasons()).containsExactly(new DeadlineRiskResult.Reason("no_deadline",
				"У инцидента нет дедлайна — риск не оценивается"));
	}

	@Test
	void missingOrZeroMedianIsUnknownBecauseTheMainFeatureIsUnavailable() {
		var noMedian = scorer.evaluate(new DeadlineRiskInput("Support",
				Instant.parse("2026-11-21T12:00:00Z"), NOW, null, 0, 0, 0, Duration.ZERO, 0, 4));
		assertThat(noMedian.risk()).isEqualTo(DeadlineRiskLevel.UNKNOWN);
		assertThat(noMedian.score()).isNull();
		assertThat(noMedian.reasons()).extracting(DeadlineRiskResult.Reason::code).containsExactly("no_history");

		var zeroMedian = scorer.evaluate(new DeadlineRiskInput("Support",
				Instant.parse("2026-11-21T12:00:00Z"), NOW, 0.0, 0, 0, 0, Duration.ZERO, 0, 4));
		assertThat(zeroMedian.risk()).isEqualTo(DeadlineRiskLevel.UNKNOWN);
		assertThat(zeroMedian.reasons()).extracting(DeadlineRiskResult.Reason::text)
				.containsExactly("Нет завершённых карточек группы «Support» — медиана решения недоступна");
	}

	@Test
	void incidentWithoutGroupIsUnknown() {
		var result = scorer.evaluate(new DeadlineRiskInput(null,
				Instant.parse("2026-11-21T12:00:00Z"), NOW, 6.0, 0, 0, 0, Duration.ZERO, 0, 4));

		assertThat(result.risk()).isEqualTo(DeadlineRiskLevel.UNKNOWN);
		assertThat(result.reasons()).extracting(DeadlineRiskResult.Reason::text)
				.containsExactly("Карточка без назначенной группы — медиана решения недоступна");
	}

	@Test
	void medianExceedingTheDeadlineIsReportedWithTheConcreteNumbers() {
		var result = scorer.evaluate(new DeadlineRiskInput("КЦ ИТ Координаторы Массовых Инцидентов",
				Instant.parse("2026-11-21T12:02:00Z"), NOW, 6.0, 0, 0, 0, Duration.ZERO, 0, 4));

		assertThat(result.risk()).isEqualTo(DeadlineRiskLevel.HIGH);
		assertThat(result.reasons()).extracting(DeadlineRiskResult.Reason::text).containsExactly(
				"Медиана группы решения «КЦ ИТ Координаторы Массовых Инцидентов»: 6.0 ч, до дедлайна 2.0 ч");
	}

	@Test
	void deadlineExtensionsAndReopeningsUseRussianPluralCounts() {
		var result = scorer.evaluate(new DeadlineRiskInput("Support",
				Instant.parse("2026-11-21T12:00:00Z"), NOW, 6.0, 1, 1, 1, Duration.ZERO, 0, 1));

		assertThat(result.reasons()).extracting(DeadlineRiskResult.Reason::text)
				.contains("Дедлайн уже продлевался 1 раз", "Группа или исполнитель менялись 1 раз",
						"Карточка переоткрывалась 1 раз");
	}

	@Test
	void severalCountedReasonsKeepTheRussianPlural() {
		var result = scorer.evaluate(new DeadlineRiskInput("Support",
				Instant.parse("2026-11-21T12:00:00Z"), NOW, 6.0, 2, 5, 11, Duration.ZERO, 0, 1));

		assertThat(result.reasons()).extracting(DeadlineRiskResult.Reason::text)
				.contains("Дедлайн уже продлевался 2 раза", "Группа или исполнитель менялись 5 раз",
						"Карточка переоткрывалась 11 раз");
	}

	@Test
	void passedDeadlineSaturatesTheMedianFeatureInsteadOfDividingByZero() {
		// The deadline is already behind: the remainder floors at MIN_REMAINING_HOURS.
		var result = scorer.evaluate(new DeadlineRiskInput("Support",
				NOW.minusSeconds(600), NOW, 6.0, 0, 0, 0, Duration.ZERO, 0, 4));

		assertThat(result.risk()).isEqualTo(DeadlineRiskLevel.HIGH);
		assertThat(result.score()).isEqualTo(-1.0 + 6.0 / 0.05 + 4 * 0.25);
	}

	@Test
	void reasonThresholdsKeepQuietFeaturesOutOfTheGrounds() {
		// 23.9 h without a status change and 4 open cards stay below the shipped thresholds.
		var result = scorer.evaluate(new DeadlineRiskInput("Support",
				Instant.parse("2026-11-21T20:00:00Z"), NOW, 1.0, 0, 0, 0,
				Duration.ofMinutes(23 * 60 + 54), 4, 1));

		assertThat(result.reasons()).extracting(DeadlineRiskResult.Reason::code).containsExactly("group_median");
	}

	@Test
	void negativeFeatureObservationsAreClampedToZero() {
		var result = scorer.evaluate(new DeadlineRiskInput("Support",
				Instant.parse("2026-11-21T12:00:00Z"), NOW, 1.0, -3, -2, -7, Duration.ZERO, -1, 1));

		// x1 = 1.0/2.0 = 0.5 and priority 1: score = -1 + 0.5 + 0.25 = -0.25 -> low.
		assertThat(result.score()).isEqualTo(-0.25);
		assertThat(result.risk()).isEqualTo(DeadlineRiskLevel.LOW);
		assertThat(result.reasons()).extracting(DeadlineRiskResult.Reason::code).containsExactly("group_median");
	}

	@Test
	void invalidConfigurationFailsFastInsteadOfSilentlyScoring() {
		assertThatThrownBy(() -> new DeadlineRiskProperties.Thresholds(1.0, 0.5))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new DeadlineRiskProperties.Thresholds(Double.NaN, 1.0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new DeadlineRiskProperties.Weights(0, 0, 0, 0, 0, 0, Double.NaN, 0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new DeadlineRiskProperties.Reasons(0, 5))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private static DeadlineRiskInput input(double remainingHours, double medianHours,
			long extensions, int priority) {
		return new DeadlineRiskInput("Support", NOW.plusSeconds((long) (remainingHours * 3600)), NOW,
				medianHours, extensions, 0, 0, Duration.ZERO, 0, priority);
	}
}
