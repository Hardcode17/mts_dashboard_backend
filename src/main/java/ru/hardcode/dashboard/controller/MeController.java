package ru.hardcode.dashboard.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.hardcode.dashboard.dto.MeResponse;

/**
 * Identity of the human user behind the Bearer token. The endpoint only reflects the already
 * validated token; it never creates or changes accounts in the identity provider.
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

	@GetMapping
	public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
		List<String> audience = jwt.getAudience();
		return new MeResponse(
				jwt.getSubject(),
				jwt.getClaimAsString("preferred_username"),
				jwt.getClaimAsString("name"),
				jwt.getClaimAsString("email"),
				audience == null ? List.of() : List.copyOf(audience)
		);
	}
}
