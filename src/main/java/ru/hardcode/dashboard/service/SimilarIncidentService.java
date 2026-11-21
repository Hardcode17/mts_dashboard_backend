package ru.hardcode.dashboard.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.hardcode.dashboard.dto.DashboardSimilarIncidentsResponse;
import ru.hardcode.dashboard.dto.DashboardSimilarIncidentsResponse.Candidate;
import ru.hardcode.dashboard.dto.DashboardSimilarIncidentsResponse.SharedAttributes;
import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.AppException;
import ru.hardcode.dashboard.exception.DashboardIncidentNotFoundException;
import ru.hardcode.dashboard.repository.DashboardIncidentRepository;
import ru.hardcode.dashboard.repository.DashboardIncidentRepository.ScoringRow;

/**
 * Read-only suggestions of similar incidents for one INC (KAN-42). The scoring is deterministic
 * and uses only the published snapshot data: a candidate shares the catalog service of the
 * selected incident and was registered inside an inclusive window around its submit date.
 * Shared scenario and error-code attributes add weight, closer registrations rank higher, and
 * equal scores fall back to the incident id. The ITSM {@code isDouble}/{@code isMaster} flags are
 * never a basis for a suggestion and no relation is created by reading.
 */
@Service
public class SimilarIncidentService {

	private static final Pattern INCIDENT_ID = Pattern.compile("INC[0-9]{6,18}");
	/** Inclusive registration window on both sides of the selected incident's submit date. */
	static final Duration WINDOW = Duration.ofHours(72);
	private static final int SERVICE_SCORE = 50;
	private static final int SCENARIO_SCORE = 20;
	private static final int ERROR_CODE_SCORE = 30;
	private static final int SAME_DAY_SCORE = 5;
	private static final int SAME_SHIFT_SCORE = 10;
	private static final Duration SAME_DAY = Duration.ofHours(24);
	private static final Duration SAME_SHIFT = Duration.ofHours(6);
	private static final Comparator<Candidate> RANK =
			Comparator.comparing(Candidate::score).reversed().thenComparing(Candidate::incidentId);

	private final DashboardIncidentRepository repository;

	public SimilarIncidentService(DashboardIncidentRepository repository) {
		this.repository = repository;
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public DashboardSimilarIncidentsResponse similar(String incidentId, String requestedLimit) {
		if (!INCIDENT_ID.matcher(incidentId).matches()) {
			throw new AppException(AppErrors.INVALID_INCIDENT_ID);
		}
		int limit = parseLimit(requestedLimit);
		var published = repository.publication();
		if (published.eventSeq() == 0) {
			throw new DashboardIncidentNotFoundException(incidentId, 0, 0);
		}
		var target = repository.findScoringRow(incidentId).orElseThrow(() ->
				new DashboardIncidentNotFoundException(incidentId, published.eventSeq(), published.revision()));
		var candidates = new ArrayList<Candidate>();
		if (target.serviceId() != null) {
			for (ScoringRow row : repository.findSimilarScoringRows(target.serviceId(), incidentId,
					target.submitDate().minus(WINDOW), target.submitDate().plus(WINDOW))) {
				candidates.add(candidate(target, row));
			}
		}
		candidates.sort(RANK);
		return new DashboardSimilarIncidentsResponse(incidentId, published.eventSeq(), published.revision(),
				candidates.stream().limit(limit).toList());
	}

	private static int parseLimit(String requestedLimit) {
		if (requestedLimit == null) {
			return 10;
		}
		try {
			int limit = Integer.parseInt(requestedLimit);
			if (limit >= 1 && limit <= 100) {
				return limit;
			}
		} catch (NumberFormatException exception) {
			throw new AppException(AppErrors.INVALID_LIMIT, exception);
		}
		throw new AppException(AppErrors.INVALID_LIMIT);
	}

	private static Candidate candidate(ScoringRow target, ScoringRow row) {
		int score = SERVICE_SCORE;
		var reasons = new ArrayList<String>(4);
		reasons.add("Совпадает сервис: «" + target.serviceKey() + "»");
		Duration difference = Duration.between(target.submitDate(), row.submitDate()).abs();
		score += proximityBonus(difference);
		reasons.add("Регистрации отличаются на " + difference(difference) + " — в пределах окна "
				+ difference(WINDOW));
		boolean sameScenario = target.scenarioKey() != null && target.scenarioKey().equals(row.scenarioKey());
		if (sameScenario) {
			score += SCENARIO_SCORE;
			reasons.add("Совпадает сценарий: «" + row.scenarioKey() + "»");
		}
		boolean sameErrorCode = errorCode(target) != null && errorCode(target).equals(errorCode(row));
		if (sameErrorCode) {
			score += ERROR_CODE_SCORE;
			reasons.add("Совпадает код ошибки: «" + errorCode(row) + "»");
		}
		return new Candidate(row.id(), score, List.copyOf(reasons), new SharedAttributes(row.productKey(),
				row.serviceKey(), sameScenario ? row.scenarioKey() : null, sameErrorCode ? errorCode(row) : null),
				row.header(), row.state(), row.priorityId(), row.submitDate(), difference.getSeconds(),
				row.isDouble(), row.isMaster());
	}

	/** The error-code signal applies only when both published records carry the same non-blank code. */
	private static String errorCode(ScoringRow row) {
		return row.errorCode() != null && !row.errorCode().isBlank() ? row.errorCode() : null;
	}

	private static int proximityBonus(Duration difference) {
		if (difference.compareTo(SAME_SHIFT) <= 0) {
			return SAME_SHIFT_SCORE;
		}
		return difference.compareTo(SAME_DAY) <= 0 ? SAME_DAY_SCORE : 0;
	}

	/** Whole hours and minutes of a difference, without a trailing zero part. */
	private static String difference(Duration difference) {
		long hours = difference.toHours();
		long minutes = difference.toMinutesPart();
		if (hours == 0) {
			return minutes + " мин";
		}
		return minutes == 0 ? hours + " ч" : hours + " ч " + minutes + " мин";
	}
}
