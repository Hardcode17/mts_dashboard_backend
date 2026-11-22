package ru.hardcode.dashboard.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.hardcode.dashboard.dto.CreateShiftHandoverRequest;
import ru.hardcode.dashboard.dto.ShiftHandoverResponse;
import ru.hardcode.dashboard.dto.ShiftHandoverResponse.Receipt;
import ru.hardcode.dashboard.service.CoordinatorAccessService;
import ru.hardcode.dashboard.service.ShiftHandoverService;

@RestController
@RequestMapping("/api/dashboard/shift-handovers")
public class ShiftHandoverController {

	private final CoordinatorAccessService access;
	private final ShiftHandoverService service;

	public ShiftHandoverController(CoordinatorAccessService access, ShiftHandoverService service) {
		this.access = access;
		this.service = service;
	}

	@GetMapping
	public ResponseEntity<ShiftHandoverResponse> list(Authentication auth) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(access.requireActiveUser(auth).id()));
	}

	@PostMapping
	public Receipt create(Authentication auth, @RequestBody CreateShiftHandoverRequest request) {
		return service.create(access.requireActiveUser(auth).id(), request);
	}

	@PostMapping("/{id}/tasks/{task}/claim")
	public ResponseEntity<Void> claim(Authentication auth, @PathVariable long id, @PathVariable long task) {
		service.claim(access.requireActiveUser(auth).id(), id, task);
		return ResponseEntity.ok().build();
	}
}
