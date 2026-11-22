package ru.hardcode.dashboard.service;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.hardcode.dashboard.dto.CoordinationTaskAssignmentResponse;
import ru.hardcode.dashboard.dto.RedirectCoordinationTaskRequest;
import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.AppException;
import ru.hardcode.dashboard.repository.CoordinationClaimRepository;
import ru.hardcode.dashboard.repository.CoordinationTaskAssignmentRepository;

@Service
public class CoordinationTaskRedirectService {

	private final CoordinationClaimRepository tasks;
	private final CoordinationTaskAssignmentRepository assignments;
	private final Clock clock;

	public CoordinationTaskRedirectService(CoordinationClaimRepository tasks,
			CoordinationTaskAssignmentRepository assignments, Clock clock) {
		this.tasks = tasks;
		this.assignments = assignments;
		this.clock = clock;
	}

	@Transactional
	public CoordinationTaskAssignmentResponse redirect(long taskId, long actor, RedirectCoordinationTaskRequest request) {
		String reason = validate(request);
		var task = tasks.lock(taskId).orElseThrow(() -> new AppException(AppErrors.COORDINATION_TASK_NOT_OPEN));
		requireHistoryAccess(taskId, actor);
		var current = assignments.active(taskId);
		long version = current.map(CoordinationTaskAssignmentRepository.Assignment::id).orElse(0L);
		if (version != request.expectedAssignmentId()) {
			throw new AppException(AppErrors.COORDINATION_ASSIGNMENT_CHANGED);
		}
		if (!Objects.equals(task.coordinatorId(), actor)
				|| !assignments.isMember(request.sourceGroupId(), actor)) {
			throw new AppException(AppErrors.COORDINATION_REDIRECT_FORBIDDEN);
		}
		if (current.isPresent() && current.get().coordinatorGroupId() != request.sourceGroupId()) {
			throw new AppException(AppErrors.COORDINATION_ASSIGNMENT_CHANGED);
		}
		if (!assignments.lockActiveGroup(request.targetGroupId())) {
			throw new AppException(AppErrors.COORDINATION_REDIRECT_INVALID);
		}
		var now = clock.instant().atOffset(ZoneOffset.UTC);
		// Existing shared tasks record their source group on the first explicit redirect.
		long previous = current.map(CoordinationTaskAssignmentRepository.Assignment::id)
				.orElseGet(() -> assignments.start(taskId, request.sourceGroupId(), actor, now));
		assignments.closeForRedirect(previous, actor, reason, now);
		long id = assignments.start(taskId, request.targetGroupId(), actor, now);
		assignments.release(taskId, now);
		return assignments.history(taskId).stream().filter(assignment -> assignment.assignmentId() == id)
				.findFirst().orElseThrow();
	}

	@Transactional(readOnly = true)
	public List<CoordinationTaskAssignmentResponse> history(long taskId, long actor) {
		requireHistoryAccess(taskId, actor);
		return assignments.history(taskId);
	}

	private void requireHistoryAccess(long taskId, long actor) {
		if (!assignments.canReadHistory(taskId, actor)) {
			throw new AppException(AppErrors.COORDINATION_REDIRECT_FORBIDDEN);
		}
	}

	private static String validate(RedirectCoordinationTaskRequest request) {
		String reason = request.reason() == null ? "" : request.reason().strip();
		if (request.sourceGroupId() == null || request.sourceGroupId() <= 0
				|| request.targetGroupId() == null || request.targetGroupId() <= 0
				|| request.sourceGroupId().equals(request.targetGroupId())
				|| request.expectedAssignmentId() == null || request.expectedAssignmentId() < 0
				|| reason.isEmpty() || reason.codePointCount(0, reason.length()) > 1000) {
			throw new AppException(AppErrors.COORDINATION_REDIRECT_INVALID);
		}
		return reason;
	}
}
