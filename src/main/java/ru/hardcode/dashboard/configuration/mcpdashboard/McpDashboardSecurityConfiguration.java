package ru.hardcode.dashboard.configuration.mcpdashboard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;
import ru.hardcode.dashboard.configuration.HumanJwtConfiguration.HumanDecoder;
import ru.hardcode.dashboard.configuration.ItsmIntegrationAuthProperties;
import ru.hardcode.dashboard.configuration.ItsmMachineJwtConfiguration.MachineDecoder;
import ru.hardcode.dashboard.dto.AppErrorResponse;
import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.service.CoordinatorAccessService;
import tools.jackson.databind.ObjectMapper;

/**
 * Dedicated stateless bearer chain for the read-only MCP Dashboard transport
 * (KAN-86). It runs before the application chain, which is left unchanged, and accepts
 * only the Streamable HTTP verbs on {@code /mcp/dashboard}.
 *
 * <p>Two caller kinds share the chain: a human coordinator (valid Keycloak token of the
 * human audience backed by an active grant) and the machine agent MVP (a dedicated
 * audience/client id/role, revalidated here, read-only and without ITSM write scopes).
 * The composite decoder tries the machine audience first and falls back to the human
 * audience; authorization decides which of the two grants the caller holds.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnDashboardMcp
@EnableConfigurationProperties(McpDashboardProperties.class)
public class McpDashboardSecurityConfiguration {

	public static final String MCP_DASHBOARD_PATH = "/mcp/dashboard";
	private static final String MCP_DASHBOARD_MATCHER = MCP_DASHBOARD_PATH + "/**";
	private static final String REALM = "mcp-dashboard";

	@Bean
	@Order(2)
	SecurityFilterChain mcpDashboardSecurityFilterChain(
			HttpSecurity http,
			MachineDecoder machineDecoder,
			HumanDecoder humanDecoder,
			ItsmIntegrationAuthProperties integrationAuth,
			McpDashboardProperties properties,
			McpDashboardAuthorizationManager access,
			ObjectMapper objectMapper
	) throws Exception {
		AuthenticationEntryPoint authenticationEntryPoint = (request, response, exception) -> {
			response.setStatus(AppErrors.AUTH_REQUIRED.getHttpStatus().value());
			response.setHeader("WWW-Authenticate", "Bearer realm=\"" + REALM + "\"");
			response.setContentType("application/json");
			response.setCharacterEncoding("UTF-8");
			objectMapper.writeValue(response.getOutputStream(),
					new AppErrorResponse(AppErrors.AUTH_REQUIRED.getCode(), AppErrors.AUTH_REQUIRED.getMessage()));
		};
		AccessDeniedHandler accessDeniedHandler = (request, response, exception) -> {
			response.setStatus(AppErrors.MCP_DASHBOARD_ACCESS_DENIED.getHttpStatus().value());
			response.setContentType("application/json");
			response.setCharacterEncoding("UTF-8");
			objectMapper.writeValue(response.getOutputStream(),
					new AppErrorResponse(AppErrors.MCP_DASHBOARD_ACCESS_DENIED.getCode(),
							AppErrors.MCP_DASHBOARD_ACCESS_DENIED.getMessage()));
		};

		return http
				.securityMatcher(MCP_DASHBOARD_PATH, MCP_DASHBOARD_MATCHER)
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint(authenticationEntryPoint)
						.accessDeniedHandler(accessDeniedHandler))
				.authorizeHttpRequests(authorize -> authorize.anyRequest().access(access))
				.oauth2ResourceServer(oauth2 -> oauth2
						.authenticationEntryPoint(authenticationEntryPoint)
						.accessDeniedHandler(accessDeniedHandler)
						.jwt(jwt -> jwt.decoder(new McpDashboardDecoder(
								machineDecoder(machineDecoder, properties, integrationAuth),
								humanDecoder.decoder()).jwt())))
				.build();
	}

	/**
	 * Machine branch of the composite decoder. When the Keycloak integration realm is
	 * fully configured, the branch requires the machine agent's own
	 * {@code mcp.dashboard.client.audience} instead of the ingest audience; otherwise it
	 * keeps the shared machine decoder bean (test configuration overrides it), which stays
	 * fail-closed in production when the integration settings are absent.
	 */
	private static JwtDecoder machineDecoder(MachineDecoder fallback, McpDashboardProperties properties,
			ItsmIntegrationAuthProperties integrationAuth) {
		if (!integrationAuth.isConfigured() || !properties.client().isConfigured()) {
			return fallback.decoder();
		}
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(integrationAuth.jwkSetUri()).build();
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(List.of(
				JwtValidators.createDefaultWithIssuer(integrationAuth.issuer()),
				audienceValidator(properties.client().audience()))));
		return decoder;
	}

	private static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
		return jwt -> jwt.getAudience() != null && jwt.getAudience().contains(audience)
				? OAuth2TokenValidatorResult.success()
				: OAuth2TokenValidatorResult.failure(new OAuth2Error(
						"invalid_token",
						"The required machine agent audience is missing",
						null));
	}

	/**
	 * Tries the machine audience first and falls back to the human audience; the human
	 * exception is surfaced when both audiences reject the token.
	 */
	static final class McpDashboardDecoder implements JwtDecoder {

		private final JwtDecoder machine;
		private final JwtDecoder human;

		McpDashboardDecoder(JwtDecoder machine, JwtDecoder human) {
			this.machine = machine;
			this.human = human;
		}

		@Override
		public Jwt decode(String token) throws JwtException {
			try {
				return machine.decode(token);
			} catch (BadJwtException rejected) {
				return human.decode(token);
			}
		}

		JwtDecoder jwt() {
			return this;
		}
	}

	/**
	 * One read grant is enough: the configured machine agent with its dedicated role, or an
	 * active coordinator grant behind the human token. Authorization runs on every call,
	 * including cursor continuations.
	 */
	@Component
	@ConditionalOnDashboardMcp
	static final class McpDashboardAuthorizationManager
			implements AuthorizationManager<RequestAuthorizationContext> {

		private final McpDashboardProperties properties;
		private final CoordinatorAccessService coordinatorAccess;

		McpDashboardAuthorizationManager(McpDashboardProperties properties, CoordinatorAccessService coordinatorAccess) {
			this.properties = properties;
			this.coordinatorAccess = coordinatorAccess;
		}

		@Override
		public AuthorizationDecision authorize(Supplier<? extends Authentication> authentication,
				RequestAuthorizationContext context) {
			Authentication auth = authentication.get();
			if (auth instanceof JwtAuthenticationToken jwt) {
				McpDashboardProperties.Client client = properties.client();
				if (client.isConfigured() && machineGrant(jwt.getToken(), client)) {
					return new AuthorizationDecision(true);
				}
			}
			try {
				coordinatorAccess.requireActiveUser(auth);
				return new AuthorizationDecision(true);
			} catch (RuntimeException denied) {
				return new AuthorizationDecision(false);
			}
		}

		private static boolean machineGrant(Jwt jwt, McpDashboardProperties.Client client) {
			return jwt.getAudience() != null && jwt.getAudience().contains(client.audience())
					&& client.clientId().equals(jwt.getClaimAsString("azp"))
					&& containsRole(jwt.getClaims(), client.clientId(), client.role());
		}

		private static boolean containsRole(Map<String, Object> claims, String clientId, String role) {
			return containsRole(claims.get("realm_access"), role)
					|| containsClientRole(claims.get("resource_access"), clientId, role)
					|| containsRole(claims.get("roles"), role);
		}

		private static boolean containsRole(Object claim, String role) {
			if (claim instanceof Map<?, ?> map) {
				return containsRole(map.get("roles"), role);
			}
			if (claim instanceof Collection<?> roles) {
				return roles.stream().anyMatch(role::equals);
			}
			return false;
		}

		private static boolean containsClientRole(Object claim, String clientId, String role) {
			if (claim instanceof Map<?, ?> clients) {
				return containsRole(clients.get(clientId), role);
			}
			return false;
		}
	}
}
