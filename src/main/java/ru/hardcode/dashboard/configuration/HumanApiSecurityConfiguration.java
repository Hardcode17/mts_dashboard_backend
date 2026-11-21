package ru.hardcode.dashboard.configuration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import ru.hardcode.dashboard.configuration.HumanJwtConfiguration.HumanDecoder;
import ru.hardcode.dashboard.exception.AppErrors;
import tools.jackson.databind.ObjectMapper;

/**
 * Stateless Bearer JWT chain for the human API under {@code /api/**}. It runs after the ITSM
 * machine chain (which owns {@code /api/integrations/itsm/**}) and before the default
 * health/form-login chain, and it accepts only tokens issued for the human audience. Without auth settings only /api/me is
 * reserved; the previous demo routes keep their existing chain.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HumanAuthProperties.class)
public class HumanApiSecurityConfiguration {

	public static final String HUMAN_API_PATH = "/api/**";
	/** Realm name of the human chain's {@code 401} challenge. */
	static final String HUMAN_REALM = "dashboard-human";
	static final RequestMatcher DASHBOARD_READS = new OrRequestMatcher(
			PathPatternRequestMatcher.withDefaults().matcher("/api/dashboard/incidents"),
			PathPatternRequestMatcher.withDefaults().matcher("/api/dashboard/incidents/{incidentId}"),
			PathPatternRequestMatcher.withDefaults().matcher("/api/dashboard/monitoring/summary"),
			PathPatternRequestMatcher.withDefaults().matcher("/api/incidents/{id}/relationships"));

	@Bean
	@Order(2)
	public SecurityFilterChain humanApiSecurityFilterChain(
			HttpSecurity http,
			HumanDecoder humanDecoder,
			HumanAuthProperties properties,
			ObjectMapper objectMapper
	) throws Exception {
		var humanEntryPoint = new StrictBearerAuthenticationEntryPoint(
				objectMapper, AppErrors.HUMAN_AUTH_REQUIRED, HUMAN_REALM);
		var dashboardEntryPoint = new StrictBearerAuthenticationEntryPoint(
				objectMapper, AppErrors.AUTH_REQUIRED, HUMAN_REALM);
		AuthenticationEntryPoint authenticationEntryPoint = (request, response, error) -> {
			var entryPoint = DASHBOARD_READS.matches(request) ? dashboardEntryPoint : humanEntryPoint;
			entryPoint.commence(request, response, error);
		};
		AccessDeniedHandler accessDeniedHandler = (request, response, exception) ->
				ApiErrorWriter.write(response, objectMapper, AppErrors.HUMAN_ACCESS_DENIED);

		return http
				.securityMatcher(properties.isRequested() ? HUMAN_API_PATH : "/api/me")
				.csrf(csrf -> csrf.disable())
				.requestCache(cache -> cache.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.logout(logout -> logout.disable())
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint(authenticationEntryPoint)
						.accessDeniedHandler(accessDeniedHandler))
				.authorizeHttpRequests(authorize -> authorize
						.anyRequest().authenticated())
				.oauth2ResourceServer(oauth2 -> oauth2
						.authenticationEntryPoint(authenticationEntryPoint)
						.accessDeniedHandler(accessDeniedHandler)
						.jwt(jwt -> jwt.decoder(humanDecoder.decoder())))
				.build();
	}
}
