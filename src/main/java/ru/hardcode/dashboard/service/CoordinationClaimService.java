package ru.hardcode.dashboard.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.hardcode.dashboard.dto.CoordinationClaimResponse;
import ru.hardcode.dashboard.dto.CoordinationWindowResponse;
import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.AppException;
import ru.hardcode.dashboard.repository.CoordinationClaimRepository;
import ru.hardcode.dashboard.repository.CoordinationTaskAssignmentRepository;
import ru.hardcode.dashboard.repository.CoordinationClaimRepository.Assignment;
import ru.hardcode.dashboard.repository.CoordinationClaimRepository.OpenTask;

@Service
public class CoordinationClaimService {

	private static final int CRITICAL_PRIORITY = 4;
	private static final long WINDOW_SECONDS = 60;

	private final CoordinationClaimRepository repository;
	private final Clock clock;
	private final CoordinationTaskAssignmentRepository assignments;

	public CoordinationClaimService(CoordinationClaimRepository repository, Clock clock,
			CoordinationTaskAssignmentRepository assignments) {
		this.repository = repository;
		this.clock = clock;
		this.assignments = assignments;
	}

	@Transactional(readOnly = true)
	public List<CoordinationClaimResponse> mine(long actor) {
		return repository.mine(actor).stream().map(CoordinationClaimService::response).toList();
	}

	@Transactional
	public CoordinationClaimResponse claim(long taskId, long actor) {
		OpenTask task = lockForActor(taskId, actor);
		return response(task.coordinatorId() != null ? task.assignment() : repository.claim(task, actor, now(), null));
	}

	@Transactional
	public CoordinationWindowResponse show(long taskId, long actor) {
		OpenTask task = lockForActor(taskId, actor);
		if (!isCritical(task) || task.coordinatorId() != null) {
			return new CoordinationWindowResponse(null, null);
		}
		var window = repository.window(taskId, actor)
				.orElseGet(() -> repository.openWindow(taskId, actor, now().plusSeconds(WINDOW_SECONDS)));
		return new CoordinationWindowResponse(window.windowId(), window.expiresAt().toInstant());
	}

	@Transactional
	public CoordinationClaimResponse accept(long taskId, UUID windowId, long actor) {
		OpenTask task = lockForActor(taskId, actor);
		var window = repository.window(taskId, actor)
				.filter(row -> row.windowId().equals(windowId))
				.filter(row -> isCritical(task))
				.orElseThrow(() -> new AppException(AppErrors.COORDINATION_WINDOW_UNAVAILABLE));
		// Sample the clock after the row lock, not before waiting for another claim.
		OffsetDateTime now = now();
		if (!now.isBefore(window.expiresAt())) {
			throw new AppException(AppErrors.COORDINATION_WINDOW_EXPIRED);
		}
		if (task.acceptedAt() != null) {
			return response(task.assignment());
		}
		if (task.coordinatorId() != null) {
			throw new AppException(AppErrors.COORDINATION_WINDOW_UNAVAILABLE);
		}
		return response(repository.claim(task, actor, now, now));
	}

	private OpenTask lockForActor(long taskId, long actor) {
		OpenTask task = repository.lock(taskId)
				.orElseThrow(() -> new AppException(AppErrors.COORDINATION_TASK_NOT_OPEN));
		if (assignments.active(taskId).filter(assignment -> !assignments.isMember(assignment.coordinatorGroupId(), actor)).isPresent()) {
			throw new AppException(AppErrors.COORDINATION_REDIRECT_FORBIDDEN);
		}
		if (task.coordinatorId() != null && task.coordinatorId() != actor) {
			throw new AppException(AppErrors.COORDINATION_TASK_TAKEN);
		}
		return task;
	}

	private static boolean isCritical(OpenTask task) {
		return task.active() && task.priorityId() == CRITICAL_PRIORITY;
	}

	private OffsetDateTime now() {
		return clock.instant().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
	}

	private static CoordinationClaimResponse response(Assignment task) {
		return new CoordinationClaimResponse(task.id(), task.incidentId(), task.coordinatorId(),
				task.acceptedAt() == null ? null : task.acceptedAt().toInstant());
	}
}
