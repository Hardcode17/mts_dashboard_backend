package ru.hardcode.dashboard.service;

import java.time.Clock;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.hardcode.dashboard.dto.CoordinatorAvailabilityResponse;
import ru.hardcode.dashboard.dto.CoordinatorAvailabilityResponse.Group;
import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.AppException;
import ru.hardcode.dashboard.repository.CoordinatorAvailabilityRepository;
import ru.hardcode.dashboard.repository.CoordinatorAvailabilityRepository.Availability;

@Service
public class CoordinatorAvailabilityService {

	private final CoordinatorAvailabilityRepository repository;
	private final Clock clock;

	public CoordinatorAvailabilityService(CoordinatorAvailabilityRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public CoordinatorAvailabilityResponse get(long userId) {
		return response(userId, repository.activeGroups(userId));
	}

	@Transactional
	public CoordinatorAvailabilityResponse set(long userId, boolean available) {
		if (!repository.lockActiveAccount(userId)) {
			throw new AppException(AppErrors.COORDINATOR_ACCESS_DENIED);
		}
		var groups = repository.activeGroups(userId);
		if (groups.isEmpty()) {
			throw new AppException(AppErrors.COORDINATOR_GROUP_REQUIRED);
		}
		repository.set(userId, available, clock.instant());
		return response(userId, groups);
	}

	private CoordinatorAvailabilityResponse response(long userId, List<Group> groups) {
		var availability = repository.find(userId).orElseGet(() -> new Availability(false, null, 0));
		return new CoordinatorAvailabilityResponse(!groups.isEmpty() && availability.available(),
				availability.availableSince(), availability.generation(), groups);
	}
}
