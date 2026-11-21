package ru.hardcode.dashboard.service.deadlinerisk;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class DeadlineRiskScorerTest {

	private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");
	private static final DeadlineRiskProperties DEFAULTS = new DeadlineRiskProperties(null, null, null, null, null);
	/** Shipped KAN-81 regression constants with hand-checkable numbers. */
	private static final DeadlineRiskProperties REGRESSION = new DeadlineRiskProperties(
			DeadlineRiskProperties.Mode.REGRESSION, null, null,
			new DeadlineRiskProperties.Regression(
					new DeadlineRiskProperties.Weights(-1.0, 1.0, 1.0, 0.5, 1.0, 0.05, 0.1, 0.25),
					new DeadlineRiskProperties.Thresholds(0.0, 1.0), null, 0.05),
			null);

	@Test
	void thresholdModeIsTheProductDefaultAndCategorizesByRemainingHours() {
		var scorer = new DeadlineRiskScorer(DEFAULTS);

		// Below N: high; inside (N, buffer): medium; above the buffer: low.
		assertThat(scorer.evaluate(input(32.91, null)).risk()).isEqualTo(DeadlineRiskLevel.HIGH);
		assertThat(scorer.evaluate(input(40.0, null)).risk()).isEqualTo(DeadlineRiskLevel.MEDIUM);
		assertThat(scorer.evaluate(input(65.83, null)).risk()).isEqualTo(DeadlineRiskLevel.MEDIUM);
		assertThat(scorer.evaluate(input(65.85, null)).risk()).isEqualTo(DeadlineRiskLevel.LOW);
		assertThat(scorer.evaluate(input(1000.0, null)).risk()).isEqualTo(DeadlineRiskLevel.LOW);
	}

	@Test
	void thresholdBoundariesAreExclusiveOnNAndInclusiveBelowTheBuffer() {
		var scorer = new DeadlineRiskScorer(DEFAULTS);

		assertThat(scorer.evaluate(input(32.92, null)).risk()).isEqualTo(DeadlineRiskLevel.MEDIUM);
		assertThat(scorer.evaluate(input(65.84, null)).risk()).isEqualTo(DeadlineRiskLevel.LOW);
	}

	@Test
	void thresholdModeExplainsTheCategoryWithTheConfiguredNumbers() {
		var scorer = new DeadlineRiskScorer(DEFAULTS);

		var high = scorer.evaluate(input(12.3, null));
		assertThat(high.risk()).isEqualTo(DeadlineRiskLevel.HIGH);
		assertThat(high.reasons()).extracting(DeadlineRiskResult.Reason::code)
				.containsExactly("remaining_below_threshold");
		assertThat(high.reasons()).extracting(DeadlineRiskResult.Reason::text)
				.containsExactly("До дедлайна 12.3 ч, порог 32.9 ч");
		assertThat(high.score()).isNull();

		var medium = scorer.evaluate(input(40.0, null));
		assertThat(medium.reasons()).extracting(DeadlineRiskResult.Reason::text)
				.containsExactly("До дедлайна 40.0 ч, буфер 65.8 ч");
	}

	@Test
	void thresholdModeAddsTheGroupMedianGroundWhenHistoryExists() {
		var scorer = new DeadlineRiskScorer(DEFAULTS);

		// The group usually takes 6 h and only 2 h are left: the median confirms the risk.
		var explained = scorer.evaluate(input(2.0, 6.0));
		assertThat(explained.risk()).isEqualTo(DeadlineRiskLevel.HIGH);
		assertThat(explained.reasons()).extracting(DeadlineRiskResult.Reason::code)
				.containsExactly("remaining_below_threshold", "group_median_exceeds");
		assertThat(explained.reasons()).extracting(DeadlineRiskResult.Reason::text).contains(
				"Медиана группы решения «Support»: 6.0 ч, остаток 2.0 ч");

		// Plenty of time: low, and the median ground stays away even with history present.
		var low = scorer.evaluate(input(100.0, 6.0));
		assertThat(low.risk()).isEqualTo(DeadlineRiskLevel.LOW);
		assertThat(low.reasons()).isEmpty();
	}

	@Test
	void thresholdModeScoresWithoutAnyGroupHistory() {
		var scorer = new DeadlineRiskScorer(DEFAULTS);

		var noHistory = scorer.evaluate(new DeadlineRiskInput(null,
				NOW.plus(Duration.ofHours(10)), NOW, null, 0, 0, 0, Duration.ZERO, 0, 4));
		assertThat(noHistory.risk()).isEqualTo(DeadlineRiskLevel.HIGH);
		assertThat(noHistory.score()).isNull();
		assertThat(noHistory.reasons()).extracting(DeadlineRiskResult.Reason::code)
				.containsExactly("remaining_below_threshold");
	}

	@Test
	void passedDeadlineStaysHighInTheThresholdMode() {
		var scorer = new DeadlineRiskScorer(DEFAULTS);

		var result = scorer.evaluate(new DeadlineRiskInput("Support", NOW.minusSeconds(600), NOW,
				6.0, 0, 0, 0, Duration.ZERO, 0, 4));
		assertThat(result.risk()).isEqualTo(DeadlineRiskLevel.HIGH);
		assertThat(result.reasons()).extracting(DeadlineRiskResult.Reason::text).contains(
				"До дедлайна -0.2 ч, порог 32.9 ч");
	}

	@Test
	void incidentWithoutDeadlineIsUnknownWithoutScoringInBothModes() {
		var expected = DeadlineRiskResult.unknown(List.of(new DeadlineRiskResult.Reason("no_deadline",
				"У инцидента нет дедлайна — риск не оценивается")));

		var threshold = new DeadlineRiskScorer(DEFAULTS)
				.evaluate(new DeadlineRiskInput("Support", null, NOW, 6.0, 1, 0, 0, Duration.ofHours(1), 0, 4));
		var regression = new DeadlineRiskScorer(REGRESSION)
				.evaluate(new DeadlineRiskInput("Support", null, NOW, 6.0, 1, 0, 0, Duration.ofHours(1), 0, 4));

		assertThat(threshold).isEqualTo(expected);
		assertThat(regression).isEqualTo(expected);
	}

	@Test
	void regressionModeSumsTheWeightedFeaturesWithClampsAndK81Thresholds() {
		var scorer = new DeadlineRiskScorer(REGRESSION);

		// x1 = 6/2 = 3, x2 = 1, x3 = 2, x4 = 1, x5 = 30, x6 = 7, x7 = 4:
		// score = -1 + 3 + 1 + 2*0.5 + 1 + 30*0.05 + 7*0.1 + 4*0.25 = 8.2 >= high(1.0).
		var result = scorer.evaluate(new DeadlineRiskInput("Support",
				Instant.parse("2026-11-21T12:00:00Z"), NOW, 6.0, 1, 2, 1,
				Duration.ofHours(30), 7, 4));

		assertThat(result.risk()).isEqualTo(DeadlineRiskLevel.HIGH);
		assertThat(result.score()).isCloseTo(8.2, offset(1e-9));
		assertThat(result.reasons()).extracting(DeadlineRiskResult.Reason::code).containsExactly(
				"group_median_exceeds", "deadline_extended", "reassigned", "reopened",
				"status_stale", "assignee_overloaded");
	}

	@Test
	void regressionModeClampsEveryFeatureToTheConfiguredBounds() {
		// x1 without the clamp would be 60000/0.05 = 1_200_000, x5 would be 10000.
		var clamped = new DeadlineRiskScorer(REGRESSION).evaluate(new DeadlineRiskInput("Support",
				Instant.parse("2026-11-21T12:00:00Z"), NOW, 60000.0, 500, 100, 50,
				Duration.ofHours(10000), 1000, 9));

		assertThat(clamped.score()).isCloseTo(-1.0 + 100 + 200 + 0.5 * 50 + 20 + 2160 * 0.05 + 100 * 0.1
				+ 4 * 0.25, offset(1e-9));
		assertThat(clamped.risk()).isEqualTo(DeadlineRiskLevel.HIGH);
	}

	@Test
	void regressionModeCategoryBoundariesAreInclusiveOnBothThresholds() {
		var scorer = new DeadlineRiskScorer(REGRESSION);

		// score exactly at the high threshold: x1 = 7/4, score = -1 + 1.75 + 0.25 = 1.0.
		assertThat(scorer.evaluate(input(4.0, 7.0)).risk()).isEqualTo(DeadlineRiskLevel.HIGH);
		assertThat(scorer.evaluate(input(4.0, 7.0)).score()).isEqualTo(1.0);
		// score exactly at the medium threshold: x1 = 3/4, score = -1 + 0.75 + 0.25 = 0.0.
		assertThat(scorer.evaluate(input(4.0, 3.0)).risk()).isEqualTo(DeadlineRiskLevel.MEDIUM);
		assertThat(scorer.evaluate(input(4.0, 3.0)).score()).isEqualTo(0.0);
		// Below medium: low.
		assertThat(scorer.evaluate(input(4.5, 3.0)).risk()).isEqualTo(DeadlineRiskLevel.LOW);
	}

	@Test
	void regressionModeKeepsQuietFeaturesOutOfTheGrounds() {
		// 23.9 h without a status change and 4 open cards stay below the shipped thresholds;
		// the median (1 h) fits the remaining 10 h, so no ground appears at all.
		var result = new DeadlineRiskScorer(REGRESSION).evaluate(new DeadlineRiskInput("Support",
				NOW.plus(Duration.ofHours(10)), NOW, 1.0, 0, 0, 0,
				Duration.ofMinutes(23 * 60 + 54), 4, 1));

		assertThat(result.reasons()).isEmpty();
	}

	@Test
	void regressionModeUsesRussianPluralCountsForCountedGrounds() {
		var result = new DeadlineRiskScorer(REGRESSION).evaluate(new DeadlineRiskInput("Support",
				Instant.parse("2026-11-21T12:00:00Z"), NOW, 6.0, 1, 1, 1, Duration.ZERO, 0, 1));

		assertThat(result.reasons()).extracting(DeadlineRiskResult.Reason::text)
				.contains("Дедлайн уже продлевался 1 раз", "Группа или исполнитель менялись 1 раз",
						"Карточка переоткрывалась 1 раз");

		var several = new DeadlineRiskScorer(REGRESSION).evaluate(new DeadlineRiskInput("Support",
				Instant.parse("2026-11-21T12:00:00Z"), NOW, 6.0, 2, 5, 11, Duration.ZERO, 0, 1));
		assertThat(several.reasons()).extracting(DeadlineRiskResult.Reason::text)
				.contains("Дедлайн уже продлевался 2 раза", "Группа или исполнитель менялись 5 раз",
						"Карточка переоткрывалась 11 раз");
	}

	@Test
	void regressionModeWithoutUsableMedianHistoryIsUnknown() {
		var noMedian = new DeadlineRiskScorer(REGRESSION).evaluate(new DeadlineRiskInput("Support",
				Instant.parse("2026-11-21T12:00:00Z"), NOW, null, 0, 0, 0, Duration.ZERO, 0, 4));
		assertThat(noMedian.risk()).isEqualTo(DeadlineRiskLevel.UNKNOWN);
		assertThat(noMedian.score()).isNull();
		assertThat(noMedian.reasons()).extracting(DeadlineRiskResult.Reason::code).containsExactly("no_history");

		var zeroMedian = new DeadlineRiskScorer(REGRESSION).evaluate(new DeadlineRiskInput("Support",
				Instant.parse("2026-11-21T12:00:00Z"), NOW, 0.0, 0, 0, 0, Duration.ZERO, 0, 4));
		assertThat(zeroMedian.reasons()).extracting(DeadlineRiskResult.Reason::text)
				.containsExactly("Нет завершённых карточек группы «Support» — медиана решения недоступна");

		var noGroup = new DeadlineRiskScorer(REGRESSION).evaluate(new DeadlineRiskInput(null,
				Instant.parse("2026-11-21T12:00:00Z"), NOW, 6.0, 0, 0, 0, Duration.ZERO, 0, 4));
		assertThat(noGroup.risk()).isEqualTo(DeadlineRiskLevel.UNKNOWN);
		assertThat(noGroup.reasons()).extracting(DeadlineRiskResult.Reason::text)
				.containsExactly("Карточка без назначенной группы — медиана решения недоступна");
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
		assertThatThrownBy(() -> new DeadlineRiskProperties.Regression(null, null,
				new DeadlineRiskProperties.Clamps(null, null, null, null, null, null, null), 0.25))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new DeadlineRiskProperties(null, -1.0, null, null, null))
				.isInstanceOf(IllegalArgumentException.class);
		// The buffer must open a medium zone above the high threshold.
		assertThatThrownBy(() -> new DeadlineRiskProperties(null, 32.92, 32.92, null, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private static DeadlineRiskInput input(double remainingHours, Double medianHours) {
		return new DeadlineRiskInput("Support", NOW.plusSeconds((long) (remainingHours * 3600)), NOW,
				medianHours, 0, 0, 0, Duration.ZERO, 0, 1);
	}
}
