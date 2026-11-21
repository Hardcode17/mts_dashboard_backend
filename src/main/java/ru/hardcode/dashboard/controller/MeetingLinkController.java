package ru.hardcode.dashboard.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.hardcode.dashboard.dto.SaveMeetingLinkRequest;
import ru.hardcode.dashboard.service.DashboardIncidentService;
import tools.jackson.databind.JsonNode;

@RestController
public class MeetingLinkController {

	public static final String PATH = "/api/incidents/{id}/meeting-link";

	private final DashboardIncidentService incidents;

	public MeetingLinkController(DashboardIncidentService incidents) {
		this.incidents = incidents;
	}

	@PutMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void save(@PathVariable String id, @RequestBody JsonNode body) {
		incidents.saveMeetingLink(id, SaveMeetingLinkRequest.from(body).meetingUrl());
	}
}
