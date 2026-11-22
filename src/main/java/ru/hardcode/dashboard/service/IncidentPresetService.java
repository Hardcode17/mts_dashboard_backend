package ru.hardcode.dashboard.service;

import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.hardcode.dashboard.dto.IncidentPresetRequest;
import ru.hardcode.dashboard.dto.IncidentPresetResponse;
import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.AppException;
import ru.hardcode.dashboard.repository.IncidentPresetRepository;

@Service
public class IncidentPresetService {

	private final IncidentPresetRepository repository;

	public IncidentPresetService(IncidentPresetRepository repository) {
		this.repository = repository;
	}

	@Transactional(readOnly = true)
	public List<IncidentPresetResponse> list(long owner) {
		return repository.list(owner);
	}

	@Transactional(readOnly = true)
	public IncidentPresetResponse get(long owner, long id) {
		return repository.find(owner, id).orElseThrow(IncidentPresetService::notFound);
	}

	@Transactional
	public IncidentPresetResponse create(long owner, IncidentPresetRequest request) {
		try {
			return repository.create(owner, request);
		} catch (DuplicateKeyException exception) {
			throw new AppException(AppErrors.INCIDENT_PRESET_NAME_EXISTS, exception);
		}
	}

	@Transactional
	public IncidentPresetResponse update(long owner, long id, IncidentPresetRequest request) {
		try {
			return repository.update(owner, id, request).orElseThrow(IncidentPresetService::notFound);
		} catch (DuplicateKeyException exception) {
			throw new AppException(AppErrors.INCIDENT_PRESET_NAME_EXISTS, exception);
		}
	}

	@Transactional
	public void delete(long owner, long id) {
		if (!repository.delete(owner, id)) {
			throw notFound();
		}
	}

	private static AppException notFound() {
		return new AppException(AppErrors.INCIDENT_PRESET_NOT_FOUND);
	}
}
