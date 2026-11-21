package ru.hardcode.dashboard.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.accept.HeaderContentNegotiationStrategy;
import org.springframework.web.context.request.ServletWebRequest;
import ru.hardcode.dashboard.exception.AppErrors;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class HealthSecurityConfiguration {

	private static final MediaType CSV = MediaType.valueOf("text/csv");

	/**
	 * Default chain for health and local browser pages. {@code /health} is open and the rest
	 * keeps the original form login and HTTP Basic rules for local access. The API itself is
	 * handled by the ordered Bearer JWT chains above ({@code /api/**}).
	 */
	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
		var accept = new HeaderContentNegotiationStrategy();
		RequestMatcher apiAccept = request -> {
			try {
				var types = accept.resolveMediaTypes(new ServletWebRequest(request)).stream()
						.filter(type -> type.getQualityValue() > 0).toList();
				var concrete = types.stream()
						.filter(type -> !type.isWildcardType() && !type.isWildcardSubtype()).toList();
				// A missing Accept header and `*/*` mean the documented default, application/json.
				// A browser lists text/html explicitly, so it still receives the form redirect.
				return concrete.isEmpty()
						? !types.isEmpty()
						: concrete.stream().anyMatch(type -> type.isCompatibleWith(MediaType.APPLICATION_JSON)
								|| type.isCompatibleWith(CSV));
			} catch (HttpMediaTypeNotAcceptableException ignored) {
				return false;
			}
		};
		return http
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers(HttpMethod.GET, "/health").permitAll()
						.anyRequest().authenticated())
				.exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
						(request, response, error) -> ApiErrorWriter.write(response, objectMapper, AppErrors.AUTH_REQUIRED),
						new AndRequestMatcher(HumanApiSecurityConfiguration.DASHBOARD_READS, apiAccept)))
				.httpBasic(Customizer.withDefaults())
				.formLogin(Customizer.withDefaults())
				.build();
	}
}
