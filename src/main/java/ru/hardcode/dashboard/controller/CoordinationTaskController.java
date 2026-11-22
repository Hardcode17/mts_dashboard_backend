package ru.hardcode.dashboard.controller;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.hardcode.dashboard.dto.CoordinationTaskAssignmentResponse;
import ru.hardcode.dashboard.dto.CoordinationTaskResponse;
import ru.hardcode.dashboard.dto.RedirectCoordinationTaskRequest;
import ru.hardcode.dashboard.service.CoordinationTaskRedirectService;
import ru.hardcode.dashboard.service.CoordinationTaskService;
import ru.hardcode.dashboard.service.CoordinatorAccessService;

@RestController
@RequestMapping("/api/dashboard/coordination-tasks")
public class CoordinationTaskController {

	private final CoordinationTaskService service;
	private final CoordinationTaskRedirectService redirects;
	private final CoordinatorAccessService access;

	public CoordinationTaskController(CoordinationTaskService service, CoordinationTaskRedirectService redirects,
			CoordinatorAccessService access) {
		this.service = service;
		this.redirects = redirects;
		this.access = access;
	}

	@GetMapping
	public List<CoordinationTaskResponse> queue(Authentication auth) {
		return service.queue(access.requireActiveUser(auth).id());
	}

	@GetMapping("/{taskId}/assignments")
	public List<CoordinationTaskAssignmentResponse> assignments(@PathVariable long taskId, Authentication auth) {
		return redirects.history(taskId, access.requireActiveUser(auth).id());
	}

	@PostMapping("/{taskId}/redirect")
	public CoordinationTaskAssignmentResponse redirect(@PathVariable long taskId,
			@RequestBody RedirectCoordinationTaskRequest request, Authentication auth) {
		return redirects.redirect(taskId, access.requireActiveUser(auth).id(), request);
	}
}
