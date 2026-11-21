package ru.hardcode.dashboard.service.deadlinerisk;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.hardcode.dashboard.dto.DeadlineRiskResponse;
import ru.hardcode.dashboard.entity.ItsmIncidentState;
import ru.hardcode.dashboard.repository.DashboardIncidentRepository.IncidentSummary;
import ru.hardcode.dashboard.repository.DeadlineRiskRepository;
import ru.hardcode.dashboard.repository.DeadlineRiskRepository.ActiveIncident;
import ru.hardcode.dashboard.repository.DeadlineRiskRepository.ScoredRisk;
import ru.hardcode.dashboard.repository.DeadlineRiskRepository.StoredRisk;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DeadlineRiskServiceTest {

	private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");
	private static final DeadlineRiskProperties DEFAULTS = new DeadlineRiskProperties(null, null, null, null, null);
	private static final DeadlineRiskProperties REGRESSION = new DeadlineRiskProperties(
			DeadlineRiskProperties.Mode.REGRESSION, null, null,
			new DeadlineRiskProperties.Regression(
					new DeadlineRiskProperties.Weights(-1.0, 1.0, 1.0, 0.5, 1.0, 0.05, 0.1, 0.25),
					new DeadlineRiskProperties.Thresholds(0.0, 1.0), null, 0.05),
			null);

	@Mock
	private DeadlineRiskRepository repository;

	private DeadlineRiskService service;

	@BeforeEach
	void setUp() {
		service = new DeadlineRiskService(repository, new DeadlineRiskScorer(DEFAULTS),
				Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());
	}

	@Test
	void recomputeStoresOneRowPerActiveIncidentWithDeadlineUsingTheScopedMedianFirst() {
		given(repository.findResolutionMedians()).willReturn(new DeadlineRiskRepository.ResolutionMedians(
				Map.of("Support", Map.of(4, 6.0)), Map.of("Support", 42.0, "КЦ ИТ Координаторы", 30.0)));
		given(repository.findActiveWithDeadline()).willReturn(List.of(
				active("INC000000001", "Support", 4, 1),
				active("INC000000002", "Support", 3, 2),
				active("INC000000003", "КЦ ИТ Координаторы", 2, 3),
				active("INC000000004", "Backlog", 1, 1)));

		service.recompute();

		ArgumentCaptor<List<ScoredRisk>> stored = ArgumentCaptor.forClass(List.class);
		verify(repository).replaceAll(stored.capture());
		List<ScoredRisk> captured = stored.getValue();
		assertThat(captured).extracting(ScoredRisk::incidentId)
				.containsExactly("INC000000001", "INC000000002", "INC000000003", "INC000000004");

		// The threshold mode needs only the deadline: one hour is left, so every row is high.
		assertThat(captured).extracting(ScoredRisk::risk)
				.containsOnly(DeadlineRiskLevel.HIGH);
		assertThat(captured).extracting(ScoredRisk::score).containsOnly((Double) null);
		assertThat(captured).extracting(ScoredRisk::evaluatedAt).containsOnly(NOW);

		// The scoped Support×P4 median (6 h) grounds the first card; the group-wide 42 h median
		// grounds the P3 card that has no scoped history.
		assertThat(captured.get(0).reasons()).contains(
				"\"code\":\"group_median_exceeds\"",
				"\"text\":\"Медиана группы решения «Support»: 6.0 ч, остаток 1.0 ч\"");
		assertThat(captured.get(1).reasons()).contains(
				"\"text\":\"Медиана группы решения «Support»: 42.0 ч, остаток 2.0 ч\"");
		// No history for Backlog: the category still stands, just without the median ground.
		assertThat(captured.get(3).reasons()).contains("\"code\":\"remaining_below_threshold\"")
				.doesNotContain("\"code\":\"group_median_exceeds\"");
	}

	@Test
	void recomputeInRegressionModeStoresTheWeightedScore() {
		service = new DeadlineRiskService(repository, new DeadlineRiskScorer(REGRESSION),
				Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());
		given(repository.findResolutionMedians()).willReturn(new DeadlineRiskRepository.ResolutionMedians(
				Map.of("Support", Map.of(4, 6.0)), Map.of()));
		given(repository.findActiveWithDeadline()).willReturn(List.of(active("INC000000001", "Support", 4, 1)));

		service.recompute();

		ArgumentCaptor<List<ScoredRisk>> stored = ArgumentCaptor.forClass(List.class);
		verify(repository).replaceAll(stored.capture());
		var scored = stored.getValue().get(0);
		assertThat(scored.risk()).isEqualTo(DeadlineRiskLevel.HIGH);
		// x1 = 6/1, x5 = 1 h without a status change, x7 = 4:
		// score = -1 + 6 + 1*0.05 + 4*0.25 = 6.05.
		assertThat(scored.score()).isEqualTo(6.05);
	}

	@Test
	void cardRiskReportsCompletedIncidentsAndMissingDeadlinesAsUnknown() {
		var completed = summary(ItsmIncidentState.Research, NOW);
		var noDeadline = summary(ItsmIncidentState.Assigned, null);

		var completedRisk = service.cardRisk(completed, NOW);
		assertThat(completedRisk.risk()).isEqualTo("unknown");
		assertThat(completedRisk.score()).isNull();
		assertThat(completedRisk.evaluatedAt()).isEqualTo(NOW);
		assertThat(completedRisk.reasons()).containsExactly(
				new DeadlineRiskResponse.Reason("not_active", "Карточка завершена — риск не оценивается"));

		var missingRisk = service.cardRisk(noDeadline, NOW);
		assertThat(missingRisk.risk()).isEqualTo("unknown");
		assertThat(missingRisk.reasons()).containsExactly(
				new DeadlineRiskResponse.Reason("no_deadline", "У инцидента нет дедлайна — риск не оценивается"));
	}

	@Test
	void cardRiskReturnsTheStoredResultOfTheLastPassUntilTheNextOneArrives() {
		given(repository.findStored("INC000000001")).willReturn(Optional.of(new StoredRisk(
				DeadlineRiskLevel.HIGH, null, NOW,
				"[{\"code\":\"remaining_below_threshold\",\"text\":\"До дедлайна 12.3 ч, порог 32.9 ч\"}]")));

		var risk = service.cardRisk(summary(ItsmIncidentState.Assigned, NOW), NOW);

		assertThat(risk.risk()).isEqualTo("high");
		assertThat(risk.evaluatedAt()).isEqualTo(NOW);
		assertThat(risk.score()).isNull();
		assertThat(risk.reasons()).containsExactly(new DeadlineRiskResponse.Reason("remaining_below_threshold",
				"До дедлайна 12.3 ч, порог 32.9 ч"));
	}

	@Test
	void cardRiskOfAnUnscoredActiveIncidentStaysUnknownInsteadOfBlockingTheCard() {
		given(repository.findStored("INC000000001")).willReturn(Optional.empty());

		var risk = service.cardRisk(summary(ItsmIncidentState.WorkInProgress, NOW), NOW);

		assertThat(risk.risk()).isEqualTo("unknown");
		assertThat(risk.score()).isNull();
		assertThat(risk.reasons()).extracting(DeadlineRiskResponse.Reason::code).containsExactly("not_evaluated");
	}

	private static ActiveIncident active(String id, String group, int priority, int remainingHours) {
		return new ActiveIncident(id, group, priority, NOW.plus(Duration.ofHours(remainingHours)),
				0, 0, 0, NOW.minus(Duration.ofHours(1)), 0);
	}

	private static IncidentSummary summary(ItsmIncidentState state, Instant deadline) {
		return new IncidentSummary("INC000000001", "Test product", "Support", state, 4,
				NOW.minus(Duration.ofHours(2)), deadline, null);
	}
}
