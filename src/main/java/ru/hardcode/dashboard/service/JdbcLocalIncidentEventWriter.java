package ru.hardcode.dashboard.service;

import java.time.Instant;
import java.util.Map;
import java.util.OptionalLong;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.hardcode.dashboard.repository.ItsmBaselineRepository;
import ru.hardcode.dashboard.repository.LocalIncidentEventRepository;
import tools.jackson.databind.ObjectMapper;

@Service
class JdbcLocalIncidentEventWriter implements LocalIncidentEventWriter {

	private final ItsmBaselineRepository installation;
	private final LocalIncidentEventRepository events;
	private final ObjectMapper json;

	JdbcLocalIncidentEventWriter(ItsmBaselineRepository installation, LocalIncidentEventRepository events,
			ObjectMapper json) {
		this.installation = installation;
		this.events = events;
		this.json = json;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public OptionalLong append(String incidentId, String kind, String deduplicationKey, Instant observedAt,
			Map<String, Object> details) {
		// Allocate under the same transaction lock as ITSM writers so polling cannot skip a late commit.
		installation.lockInstallation();
		return events.append(incidentId, kind, deduplicationKey, observedAt, json.writeValueAsString(details));
	}
}
