package ru.hardcode.dashboard.service;

import java.util.List;

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

	/** Joins publication transaction after the incident projection has been written. */
	public void observeEntries(String sourceId, long revision) {
		if (externalGroup.isBlank()) {
			return;
		}
		for (String incidentId : repository.enteredIncidents(sourceId, revision, externalGroup)) {
			repository.insertOpen(incidentId);
		}
	}

	@Transactional(readOnly = true)
	public List<CoordinationTaskResponse> queue() {
		return repository.listOpen().stream()
				.map(task -> new CoordinationTaskResponse(task.taskId(), task.incidentId(), task.createdAt()))
				.toList();
	}
}
