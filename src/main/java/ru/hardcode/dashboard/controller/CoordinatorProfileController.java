package ru.hardcode.dashboard.controller;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.hardcode.dashboard.dto.CoordinatorProfileResponse;
import ru.hardcode.dashboard.service.CoordinatorAccessService;

@RestController
public class CoordinatorProfileController {

	private final CoordinatorAccessService accessService;

	public CoordinatorProfileController(CoordinatorAccessService accessService) {
		this.accessService = accessService;
	}

	@GetMapping("/api/me")
	public CoordinatorProfileResponse me(Authentication authentication) {
		var user = accessService.requireActiveUser(authentication);
		return new CoordinatorProfileResponse(user.id(), user.identitySubject(), user.login(), user.displayName());
	}
}
