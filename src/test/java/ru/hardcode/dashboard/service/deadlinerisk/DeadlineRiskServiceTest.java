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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DeadlineRiskServiceTest {

	private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");

	@Mock
	private DeadlineRiskRepository repository;

	private DeadlineRiskService service;

	@BeforeEach
	void setUp() {
		service = new DeadlineRiskService(repository, new DeadlineRiskScorer(new DeadlineRiskProperties(null, null, null)),
				Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());
	}

	@Test
	void recomputeScoresEveryActiveIncidentWithDeadlineUsingTheGroupPriorityMedianFirst() {
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

		// Priority 4 of Support uses the group×priority median of 6 h over the group fallback.
		var scoped = captured.get(0);
		assertThat(scoped.risk()).isEqualTo(DeadlineRiskLevel.HIGH);
		assertThat(scoped.score()).isEqualTo(-1.0 + 6.0 + 0.05 + 1.0);
		assertThat(scoped.evaluatedAt()).isEqualTo(NOW);

		// Priority 3 of Support has no scoped median and falls back to the group-wide 42 h.
		var fallback = captured.get(1);
		assertThat(fallback.risk()).isEqualTo(DeadlineRiskLevel.HIGH);
		assertThat(fallback.score()).isEqualTo(-1.0 + 42.0 / 2.0 + 0.05 + 0.25 * 3);

		// No completed history for a group keeps the incident readable as unknown.
		var unknown = captured.get(3);
		assertThat(unknown.risk()).isEqualTo(DeadlineRiskLevel.UNKNOWN);
		assertThat(unknown.score()).isNull();
		assertThat(unknown.reasons()).contains("\"code\":\"no_history\"");
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
				DeadlineRiskLevel.HIGH, 4.0, NOW,
				"[{\"code\":\"group_median_exceeds\",\"text\":\"Медиана группы решения «Support»: 6.0 ч, до дедлайна 2.0 ч\"}]")));

		var risk = service.cardRisk(summary(ItsmIncidentState.Assigned, NOW), NOW);

		assertThat(risk.risk()).isEqualTo("high");
		assertThat(risk.evaluatedAt()).isEqualTo(NOW);
		assertThat(risk.score()).isEqualTo(4.0);
		assertThat(risk.reasons()).containsExactly(new DeadlineRiskResponse.Reason("group_median_exceeds",
				"Медиана группы решения «Support»: 6.0 ч, до дедлайна 2.0 ч"));
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
