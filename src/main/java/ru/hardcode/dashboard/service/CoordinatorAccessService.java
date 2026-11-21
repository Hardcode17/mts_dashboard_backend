package ru.hardcode.dashboard.service;

import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import ru.hardcode.dashboard.entity.CoordinatorUser;
import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.AppException;
import ru.hardcode.dashboard.repository.CoordinatorUserRepository;

@Service
public class CoordinatorAccessService {

	private final CoordinatorUserRepository repository;
	private final Set<String> userClientIds;

	public CoordinatorAccessService(CoordinatorUserRepository repository,
			@Value("${coordinator.access.user-client-ids:dashboard-web}") Set<String> userClientIds) {
		this.repository = repository;
		this.userClientIds = Set.copyOf(userClientIds);
	}

	@Transactional(readOnly = true)
	public CoordinatorUser requireActiveUser(Authentication authentication) {
		// The resource-server chain must have verified signature, issuer, audience and expiration first.
		if (!(authentication instanceof JwtAuthenticationToken token) || !token.isAuthenticated()) {
			throw new AppException(AppErrors.COORDINATOR_ACCESS_DENIED);
		}
		var jwt = token.getToken();
		String issuer = jwt.getClaimAsString("iss");
		String subject = jwt.getSubject();
		String clientId = jwt.getClaimAsString("azp");
		// Allow only configured interactive clients. Never infer a human from a login or service-account name.
		if (!StringUtils.hasText(issuer) || !StringUtils.hasText(subject)
				|| !StringUtils.hasText(clientId) || !userClientIds.contains(clientId)) {
			throw new AppException(AppErrors.COORDINATOR_ACCESS_DENIED);
		}
		return repository.findByIdentity(issuer, subject)
				.filter(CoordinatorUser::active)
				.orElseThrow(() -> new AppException(AppErrors.COORDINATOR_ACCESS_DENIED));
	}
}
