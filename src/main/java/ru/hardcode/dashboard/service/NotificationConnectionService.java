package ru.hardcode.dashboard.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.hardcode.dashboard.dto.NotificationConnectionRequest;
import ru.hardcode.dashboard.dto.NotificationConnectionResponse;
import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.AppException;
import ru.hardcode.dashboard.repository.NotificationConnectionRepository;
import ru.hardcode.dashboard.service.NotificationChannelAdapter.Destination;

@Service
public class NotificationConnectionService {

	private final NotificationConnectionRepository repository;

	public NotificationConnectionService(NotificationConnectionRepository repository) {
		this.repository = repository;
	}

	@Transactional(readOnly = true)
	public List<NotificationConnectionResponse> list(long userId) {
		return repository.findAll(userId);
	}

	@Transactional(readOnly = true)
	public NotificationConnectionResponse get(long userId, long id) {
		return repository.find(userId, id)
				.orElseThrow(() -> new AppException(AppErrors.NOTIFICATION_CONNECTION_NOT_FOUND));
	}

	@Transactional
	public NotificationConnectionResponse create(long userId, NotificationConnectionRequest request) {
		long id = repository.create(userId, typeId(request), request);
		return savePriorities(id, request);
	}

	@Transactional
	public NotificationConnectionResponse update(long userId, long id, NotificationConnectionRequest request) {
		if (!repository.update(userId, id, typeId(request), request)) {
			throw new AppException(AppErrors.NOTIFICATION_CONNECTION_NOT_FOUND);
		}
		return savePriorities(id, request);
	}

	@Transactional(readOnly = true)
	public List<Destination> findDestinations(long recipientUserId, int priorityId) {
		return repository.findDestinations(recipientUserId, priorityId);
	}

	private long typeId(NotificationConnectionRequest request) {
		return repository.findTypeId(request.channel())
				.orElseThrow(() -> new AppException(AppErrors.INVALID_NOTIFICATION_CONNECTION));
	}

	private NotificationConnectionResponse savePriorities(long id, NotificationConnectionRequest request) {
		repository.replacePriorities(id, request.priorityIds());
		return new NotificationConnectionResponse(id, request.channel(), request.address(), request.active(),
				request.priorityIds());
	}
}
