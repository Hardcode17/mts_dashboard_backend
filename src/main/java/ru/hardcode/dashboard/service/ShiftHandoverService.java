package ru.hardcode.dashboard.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.hardcode.dashboard.dto.CreateShiftHandoverRequest;
import ru.hardcode.dashboard.dto.ShiftHandoverResponse;
import ru.hardcode.dashboard.dto.ShiftHandoverResponse.Batch;
import ru.hardcode.dashboard.dto.ShiftHandoverResponse.Receipt;
import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.AppException;
import ru.hardcode.dashboard.repository.ShiftHandoverRepository;

@Service
public class ShiftHandoverService {

	private final ShiftHandoverRepository repository;
	private final Clock clock;

	public ShiftHandoverService(ShiftHandoverRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public ShiftHandoverResponse list(long actor) {
		var incoming = new ArrayList<Batch>();
		var outgoing = new ArrayList<Batch>();
		for (var header : repository.headers(actor)) {
			var batch = new Batch(header.id(), header.senderId(), header.senderName(), header.recipientId(),
					header.recipientName(), header.createdAt(), header.summary(), repository.items(header.id()));
			(header.recipientId() == actor ? incoming : outgoing).add(batch);
		}
		return new ShiftHandoverResponse(repository.recipients(actor), repository.owned(actor), incoming, outgoing);
	}

	@Transactional
	public Receipt create(long actor, CreateShiftHandoverRequest request) {
		String summary = request.summary() == null ? "" : request.summary().strip();
		if (request.requestId() == null || request.recipientId() == null || request.recipientId() == actor
				|| request.taskIds() == null || request.taskIds().isEmpty() || request.taskIds().size() > 1000
				|| request.taskIds().stream().anyMatch(id -> id == null || id <= 0)
				|| summary.codePointCount(0, summary.length()) > 2000) {
			throw new AppException(AppErrors.SHIFT_HANDOVER_INVALID);
		}
		List<Long> tasks = request.taskIds().stream().distinct().sorted().toList();
		Map<Long, String> situations = normalizeSituations(tasks, request.situationKeys());
		repository.lockSender(actor);
		var existing = repository.replay(actor, request.requestId());
		if (existing.isPresent()) {
			var batch = existing.get();
			if (batch.recipientId() != request.recipientId() || !batch.summary().equals(summary)
					|| !repository.items(batch.id()).stream().collect(Collectors.toMap(
							item -> item.taskId(), item -> item.situationKey())).equals(situations)) {
				throw new AppException(AppErrors.SHIFT_HANDOVER_CONFLICT);
			}
			return new Receipt(batch.id());
		}
		long recipient = request.recipientId();
		if (repository.recipients(actor).stream().noneMatch(person -> person.id() == recipient)) {
			throw new AppException(AppErrors.SHIFT_HANDOVER_FORBIDDEN);
		}
		if (!repository.lockOwned(tasks, actor).equals(tasks)) {
			throw new AppException(AppErrors.SHIFT_HANDOVER_CONFLICT);
		}
		repository.revokeStale(tasks, now());
		if (repository.hasPending(tasks)) {
			throw new AppException(AppErrors.SHIFT_HANDOVER_CONFLICT);
		}
		requireTaskGroups(tasks, recipient);
		return new Receipt(repository.create(actor, recipient, request.requestId(), situations, summary, now()));
	}

	@Transactional
	public void claim(long actor, long handover, long task) {
		var entries = repository.lockSituation(handover, task);
		if (entries.isEmpty()) {
			throw new AppException(AppErrors.SHIFT_HANDOVER_NOT_FOUND);
		}
		var first = entries.getFirst();
		if (first.recipientId() != actor) {
			throw new AppException(AppErrors.SHIFT_HANDOVER_FORBIDDEN);
		}
		if (entries.stream().allMatch(entry -> entry.claimedAt() != null)) {
			return;
		}
		if (entries.stream().anyMatch(entry -> !entry.available() || entry.claimedAt() != null
				|| !Objects.equals(entry.coordinatorId(), entry.senderId()))) {
			throw new AppException(AppErrors.SHIFT_HANDOVER_CONFLICT);
		}
		if (repository.recipients(first.senderId()).stream().noneMatch(person -> person.id() == actor)) {
			throw new AppException(AppErrors.SHIFT_HANDOVER_FORBIDDEN);
		}
		var taskIds = entries.stream().map(ShiftHandoverRepository.Claim::taskId).toList();
		requireTaskGroups(taskIds, actor);
		repository.accept(handover, taskIds, actor, now());
	}

	private void requireTaskGroups(List<Long> tasks, long actor) {
		if (repository.anyOutsideGroups(tasks, actor)) {
			throw new AppException(AppErrors.SHIFT_HANDOVER_FORBIDDEN);
		}
	}

	private static Map<Long, String> normalizeSituations(List<Long> tasks, Map<Long, String> declared) {
		var situations = new LinkedHashMap<Long, String>();
		for (long task : tasks) {
			situations.put(task, "task:" + task);
		}
		if (declared != null) {
			declared.forEach((task, key) -> {
				if (!situations.containsKey(task) || key == null || key.isBlank() || key.length() > 140) {
					throw new AppException(AppErrors.SHIFT_HANDOVER_INVALID);
				}
				situations.put(task, "group:" + key.strip());
			});
		}
		return situations;
	}

	private OffsetDateTime now() {
		return clock.instant().atOffset(ZoneOffset.UTC);
	}
}
