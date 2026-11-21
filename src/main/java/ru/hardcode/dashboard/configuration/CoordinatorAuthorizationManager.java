package ru.hardcode.dashboard.configuration;

import java.util.function.Supplier;

import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;
import ru.hardcode.dashboard.exception.AppException;
import ru.hardcode.dashboard.service.CoordinatorAccessService;

@Component
public class CoordinatorAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

	private final CoordinatorAccessService accessService;

	public CoordinatorAuthorizationManager(CoordinatorAccessService accessService) {
		this.accessService = accessService;
	}

	@Override
	public AuthorizationDecision authorize(Supplier<? extends Authentication> authentication,
			RequestAuthorizationContext context) {
		try {
			accessService.requireActiveUser(authentication.get());
			return new AuthorizationDecision(true);
		} catch (AppException denied) {
			return new AuthorizationDecision(false);
		}
	}
}
