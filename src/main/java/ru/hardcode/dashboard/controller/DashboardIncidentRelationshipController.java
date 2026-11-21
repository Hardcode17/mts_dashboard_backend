package ru.hardcode.dashboard.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.hardcode.dashboard.dto.DashboardIncidentRelationshipResponse;
import ru.hardcode.dashboard.service.DashboardIncidentService;

@RestController
@RequestMapping("/api/incidents")
public class DashboardIncidentRelationshipController {

	private final DashboardIncidentService service;

	public DashboardIncidentRelationshipController(DashboardIncidentService service) {
		this.service = service;
	}

	@GetMapping(path = "/{id}/relationships", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<DashboardIncidentRelationshipResponse> relationships(@PathVariable String id) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.relationships(id));
	}
}
