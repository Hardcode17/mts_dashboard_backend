package ru.hardcode.dashboard.controller;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.hardcode.dashboard.dto.CoordinatorAvailabilityResponse;
import ru.hardcode.dashboard.dto.MeResponse;
import ru.hardcode.dashboard.dto.SetCoordinatorAvailabilityRequest;
import ru.hardcode.dashboard.service.CoordinatorAccessService;
import ru.hardcode.dashboard.service.CoordinatorAvailabilityService;

/**
 * Identity of the human user behind the Bearer token. The endpoint only reflects the already
 * validated token; it never creates or changes accounts in the identity provider.
 */
@RestController
@RequestMapping("/api/me")
public class MeController {
	private final CoordinatorAccessService access;
	private final CoordinatorAvailabilityService availability;

	public MeController(CoordinatorAccessService access, CoordinatorAvailabilityService availability) {
		this.access = access;
		this.availability = availability;
	}

	@GetMapping
	public MeResponse me(@AuthenticationPrincipal Jwt jwt, Authentication authentication) {
		var profile = access.requireActiveUser(authentication);
		List<String> audience = jwt.getAudience();
		return new MeResponse(
				jwt.getSubject(),
				jwt.getClaimAsString("preferred_username"),
				jwt.getClaimAsString("name"),
				jwt.getClaimAsString("email"),
				audience == null ? List.of() : List.copyOf(audience), profile.id(), profile.identitySubject(),
				profile.login(), profile.displayName(), availability.get(profile.id())
		);
	}

	@PutMapping("/availability")
	public CoordinatorAvailabilityResponse setAvailability(Authentication authentication,
			@RequestBody SetCoordinatorAvailabilityRequest request) {
		return availability.set(access.requireActiveUser(authentication).id(), request.available());
	}
}
