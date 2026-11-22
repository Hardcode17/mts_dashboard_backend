package ru.hardcode.dashboard.service;

import java.util.List;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.hardcode.dashboard.dto.CoordinationTaskResponse;
import ru.hardcode.dashboard.repository.CoordinationTaskRepository;

@Service
public class CoordinationTaskService {

	private final CoordinationTaskRepository repository;
	private final String externalGroup;

	public CoordinationTaskService(CoordinationTaskRepository repository,
			@Value("${coordination.external-group:КЦ ИТ Координаторы Массовых Инцидентов}") String externalGroup) {
		this.repository = repository;
		this.externalGroup = externalGroup;
	}

	/** Joins publication transaction; PATCH updates never modify the official baseline. */
	public void observeHandoffs(String sourceId, long version, String snapshot, Instant observedAt, Long entryRevision) {
		repository.observeHandoffs(sourceId, version, snapshot, externalGroup, observedAt, entryRevision);
	}

	@Transactional(readOnly = true)
	public List<CoordinationTaskResponse> queue(long actor) {
		return repository.listOpen(actor).stream()
				.map(task -> new CoordinationTaskResponse(task.taskId(), task.incidentId(), task.createdAt()))
				.toList();
	}
}
