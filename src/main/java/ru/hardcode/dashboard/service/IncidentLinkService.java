package ru.hardcode.dashboard.service;

import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.hardcode.dashboard.dto.ConfirmedIncidentLinkResponse;
import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.AppException;
import ru.hardcode.dashboard.exception.DashboardIncidentNotFoundException;
import ru.hardcode.dashboard.repository.DashboardIncidentRepository;
import ru.hardcode.dashboard.repository.IncidentLinkRepository;

@Service
public class IncidentLinkService {

	private static final String RELATED_TO = "related_to";
	private static final Set<String> KINDS = Set.of(RELATED_TO, "causes", "duplicate_of");

	private final DashboardIncidentRepository incidents;
	private final IncidentLinkRepository links;

	public IncidentLinkService(DashboardIncidentRepository incidents, IncidentLinkRepository links) {
		this.incidents = incidents;
		this.links = links;
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public ConfirmedIncidentLinkResponse confirm(String source, String target, String kind, long userId) {
		DashboardIncidentService.validateIncidentId(source);
		DashboardIncidentService.validateIncidentId(target);
		if (kind == null || !KINDS.contains(kind) || source.equals(target)) {
			throw new AppException(AppErrors.INVALID_INCIDENT_LINK);
		}
		requirePublished(source);
		requirePublished(target);
		if (RELATED_TO.equals(kind) && source.compareTo(target) > 0) {
			return links.confirm(target, source, kind, userId);
		}
		return links.confirm(source, target, kind, userId);
	}

	private void requirePublished(String incidentId) {
		if (!incidents.isPublished(incidentId)) {
			var publication = incidents.publication();
			throw new DashboardIncidentNotFoundException(incidentId, publication.eventSeq(), publication.revision());
		}
	}
}
