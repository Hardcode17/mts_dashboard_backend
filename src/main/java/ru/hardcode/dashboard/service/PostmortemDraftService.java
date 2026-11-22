package ru.hardcode.dashboard.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.hardcode.dashboard.dto.PostmortemDraftResponse;
import ru.hardcode.dashboard.dto.PostmortemDraftResponse.EvidenceFact;
import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.AppException;
import ru.hardcode.dashboard.repository.PostmortemDraftRepository;
import tools.jackson.databind.ObjectMapper;

@Service
public class PostmortemDraftService {

	private static final Pattern INCIDENT_ID = Pattern.compile("INC[0-9]{6,18}");
	private final PostmortemDraftRepository repository;
	private final ObjectMapper json;

	public PostmortemDraftService(PostmortemDraftRepository repository, ObjectMapper json) {
		this.repository = repository;
		this.json = json;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void capture(long revision) {
		for (var resolution : repository.resolutions(revision)) {
			String path = "/api/dashboard/incidents/" + resolution.incidentId();
			var timeline = repository.timeline(resolution, path);
			var notes = repository.notes(resolution, path);
			var run = repository.latestRun(resolution);
			List<EvidenceFact> evidence = run.map(id -> repository.evidence(id, resolution)).orElseGet(List::of);
			var gaps = new ArrayList<String>();
			if (evidence.isEmpty()) {
				gaps.add("EVIDENCE_UNAVAILABLE");
			}
			run.ifPresent(id -> repository.unavailableSources(id)
					.forEach(source -> gaps.add("SOURCE_UNAVAILABLE_" + source)));
			var draft = new PostmortemDraftResponse(resolution.incidentId(), resolution.eventSeq(),
					resolution.observedAt(), resolution.text(), null, timeline, notes, evidence, gaps);
			repository.insert(resolution, json.writeValueAsString(draft));
		}
	}

	@Transactional(readOnly = true)
	public PostmortemDraftResponse latest(String incidentId) {
		if (!INCIDENT_ID.matcher(incidentId).matches()) {
			throw new AppException(AppErrors.INVALID_INCIDENT_ID);
		}
		return repository.latest(incidentId)
				.map(draft -> json.readValue(draft, PostmortemDraftResponse.class))
				.orElseThrow(() -> new AppException(AppErrors.POSTMORTEM_DRAFT_NOT_FOUND, Map.of("incidentId", incidentId)));
	}
}
