package ru.hardcode.dashboard.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.hardcode.dashboard.dto.CoordinationClaimResponse;
import ru.hardcode.dashboard.dto.CoordinationWindowResponse;
import ru.hardcode.dashboard.service.CoordinatorAccessService;
import ru.hardcode.dashboard.service.CoordinationClaimService;

@RestController
@RequestMapping("/api/dashboard/coordination-tasks")
public class CoordinationClaimController {

	private final CoordinatorAccessService access;
	private final CoordinationClaimService service;

	public CoordinationClaimController(CoordinatorAccessService access, CoordinationClaimService service) {
		this.access = access;
		this.service = service;
	}

	@GetMapping("/mine")
	public ResponseEntity<List<CoordinationClaimResponse>> mine(Authentication auth) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(service.mine(access.requireActiveUser(auth).id()));
	}

	@PostMapping("/{taskId}/claim")
	public CoordinationClaimResponse claim(@PathVariable long taskId, Authentication auth) {
		return service.claim(taskId, access.requireActiveUser(auth).id());
	}

	@PostMapping("/{taskId}/fullscreen-window")
	public CoordinationWindowResponse show(@PathVariable long taskId, Authentication auth) {
		return service.show(taskId, access.requireActiveUser(auth).id());
	}

	@PostMapping("/{taskId}/fullscreen-window/{windowId}/accept")
	public CoordinationClaimResponse accept(@PathVariable long taskId, @PathVariable UUID windowId,
			Authentication auth) {
		return service.accept(taskId, windowId, access.requireActiveUser(auth).id());
	}
}
