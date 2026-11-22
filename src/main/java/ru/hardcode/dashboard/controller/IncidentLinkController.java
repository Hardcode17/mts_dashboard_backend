package ru.hardcode.dashboard.controller;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ru.hardcode.dashboard.dto.ConfirmIncidentLinkRequest;
import ru.hardcode.dashboard.dto.ConfirmedIncidentLinkResponse;
import ru.hardcode.dashboard.service.CoordinatorAccessService;
import ru.hardcode.dashboard.service.IncidentLinkService;

@RestController
public class IncidentLinkController {

	private final IncidentLinkService links;
	private final CoordinatorAccessService access;

	public IncidentLinkController(IncidentLinkService links, CoordinatorAccessService access) {
		this.links = links;
		this.access = access;
	}

	@PostMapping(path = "/api/dashboard/incidents/{incidentId}/confirmed-links",
			consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ConfirmedIncidentLinkResponse confirm(@PathVariable String incidentId,
			@RequestBody ConfirmIncidentLinkRequest request, Authentication auth) {
		return links.confirm(incidentId, request.targetIncidentId(), request.kind(), access.requireActiveUser(auth).id());
	}
}
