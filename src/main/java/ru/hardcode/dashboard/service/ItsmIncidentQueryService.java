package ru.hardcode.dashboard.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.hardcode.dashboard.dto.ItsmBaseline;
import ru.hardcode.dashboard.dto.ItsmIncidentListResponse;
import ru.hardcode.dashboard.dto.ItsmIncidentResponse;

/**
 * Read model of the installed ITSM baseline. The response objects only expose what the
 * dashboard needs; external people stay distinct from application accounts.
 */
@Service
public class ItsmIncidentQueryService {

	private final ItsmBaselineService baselineService;

	public ItsmIncidentQueryService(ItsmBaselineService baselineService) {
		this.baselineService = baselineService;
	}

	@Transactional(readOnly = true)
	public ItsmIncidentListResponse currentIncidents() {
		return toResponse(baselineService.readBaseline());
	}

	static ItsmIncidentListResponse toResponse(ItsmBaseline baseline) {
		var incidents = baseline.incidents().stream()
				.map(incident -> new ItsmIncidentResponse(
						incident.id(),
						incident.state(),
						incident.priorityId(),
						incident.submitDate(),
						incident.deadline(),
						incident.completionDate(),
						String.valueOf(incident.productId()),
						incident.serviceId() == null ? null : String.valueOf(incident.serviceId()),
						incident.assignedPersonId() == null ? null : String.valueOf(incident.assignedPersonId())
				))
				.toList();
		var snapshot = baseline.snapshot();
		var snapshotResponse = snapshot == null
				? null
				: new ItsmIncidentListResponse.Snapshot(
						snapshot.batchId(),
						snapshot.sourceId(),
						snapshot.sourceVersion(),
						snapshot.appliedAt()
				);
		return new ItsmIncidentListResponse(snapshotResponse, incidents.size(), incidents);
	}
}
