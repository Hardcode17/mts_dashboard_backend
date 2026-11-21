package ru.hardcode.dashboard.service.deadlinerisk;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.hardcode.dashboard.dto.DeadlineRiskResponse;
import ru.hardcode.dashboard.repository.DashboardIncidentRepository.IncidentSummary;
import ru.hardcode.dashboard.repository.DeadlineRiskRepository;
import ru.hardcode.dashboard.repository.DeadlineRiskRepository.ActiveIncident;
import ru.hardcode.dashboard.repository.DeadlineRiskRepository.ResolutionMedians;
import ru.hardcode.dashboard.repository.DeadlineRiskRepository.ScoredRisk;
import ru.hardcode.dashboard.repository.DeadlineRiskRepository.StoredRisk;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * KAN-82 deadline risk: the scoring pass over active incidents with a deadline and the card-side
 * read of its result. The pass is a full refresh triggered by every committed snapshot publication
 * — no queue, no partial states; a failed pass is simply repeated by the next publication.
 */
@Service
public class DeadlineRiskService {

	private static final TypeReference<List<DeadlineRiskResponse.Reason>> REASONS = new TypeReference<>() {};

	private final DeadlineRiskRepository repository;
	private final DeadlineRiskScorer scorer;
	private final Clock clock;
	private final ObjectMapper json;

	public DeadlineRiskService(DeadlineRiskRepository repository, DeadlineRiskScorer scorer, Clock clock,
			ObjectMapper json) {
		this.repository = repository;
		this.scorer = scorer;
		this.clock = clock;
		this.json = json;
	}

	/**
	 * One full scoring pass. Every active incident with a deadline gets a row — scored, or
	 * {@code unknown} with the reason when the group has no resolution history yet. A fresh
	 * transaction: the wake-up may run right after another committed publication.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recompute() {
		Instant now = clock.instant();
		ResolutionMedians medians = repository.findResolutionMedians();
		var scored = new ArrayList<ScoredRisk>();
		for (ActiveIncident incident : repository.findActiveWithDeadline()) {
			var result = scorer.evaluate(new DeadlineRiskInput(incident.assignedGroupName(), incident.deadline(),
					now, medianFor(medians, incident.assignedGroupName(), incident.priorityId()),
					incident.deadlineExtensions(), incident.reassignments(), incident.reopenings(),
					Duration.between(incident.lastStatusChange(), now), incident.assigneeOpenIncidents(),
					incident.priorityId()));
			scored.add(new ScoredRisk(incident.id(), result.risk(), result.score(), now,
					json.writeValueAsString(result.reasons())));
		}
		repository.replaceAll(scored);
	}

	/**
	 * The card-side risk of one incident: the stored result of the last pass, or the derived
	 * {@code unknown} when the incident is completed, has no deadline, or has not been scored yet.
	 */
	@Transactional(readOnly = true)
	public DeadlineRiskResponse cardRisk(IncidentSummary incident, Instant now) {
		if (incident.state().isCompleted()) {
			return unknown(now, "not_active", "Карточка завершена — риск не оценивается");
		}
		if (incident.deadline() == null) {
			return unknown(now, "no_deadline", "У инцидента нет дедлайна — риск не оценивается");
		}
		return repository.findStored(incident.id())
				.map(this::toResponse)
				.orElseGet(() -> unknown(now, "not_evaluated", "Оценка риска ещё не выполнена"));
	}

	private DeadlineRiskResponse toResponse(StoredRisk stored) {
		return new DeadlineRiskResponse(stored.risk().wireName(), stored.evaluatedAt(),
				stored.reasons() == null ? List.of() : readReasons(stored.reasons()), stored.score());
	}

	private List<DeadlineRiskResponse.Reason> readReasons(String reasons) {
		try {
			return json.readValue(reasons, REASONS);
		} catch (RuntimeException unparseable) {
			// Reasons are written by the same writer; never mask the assessment behind them.
			return List.of();
		}
	}

	private static DeadlineRiskResponse unknown(Instant evaluatedAt, String code, String text) {
		return new DeadlineRiskResponse(DeadlineRiskLevel.UNKNOWN.wireName(), evaluatedAt,
				List.of(new DeadlineRiskResponse.Reason(code, text)), null);
	}

	private static Double medianFor(ResolutionMedians medians, String group, int priority) {
		if (group == null) {
			return null;
		}
		var byPriority = medians.byGroupAndPriority().get(group);
		Double scoped = byPriority == null ? null : byPriority.get(priority);
		return scoped != null && scoped > 0 ? scoped : medians.byGroup().get(group);
	}
}
