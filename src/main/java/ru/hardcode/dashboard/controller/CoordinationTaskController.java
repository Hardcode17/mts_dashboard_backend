package ru.hardcode.dashboard.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.hardcode.dashboard.dto.CoordinationTaskResponse;
import ru.hardcode.dashboard.service.CoordinationTaskService;

@RestController
public class CoordinationTaskController {

	private final CoordinationTaskService service;

	public CoordinationTaskController(CoordinationTaskService service) {
		this.service = service;
	}

	@GetMapping("/api/dashboard/coordination-tasks")
	public List<CoordinationTaskResponse> queue() {
		return service.queue();
	}
}
