package ru.hardcode.dashboard.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.hardcode.dashboard.dto.ItsmIncidentListResponse;
import ru.hardcode.dashboard.service.ItsmIncidentQueryService;

/**
 * Dashboard read access to the installed ITSM baseline. Uses the regular authenticated
 * application security chain.
 */
@RestController
@RequestMapping("/api/itsm")
public class ItsmIncidentController {

	private final ItsmIncidentQueryService queryService;

	public ItsmIncidentController(ItsmIncidentQueryService queryService) {
		this.queryService = queryService;
	}

	@GetMapping("/incidents")
	public ItsmIncidentListResponse incidents() {
		return queryService.currentIncidents();
	}
}
