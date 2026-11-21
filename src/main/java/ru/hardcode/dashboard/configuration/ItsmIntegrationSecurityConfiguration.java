package ru.hardcode.dashboard.configuration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import ru.hardcode.dashboard.configuration.ItsmMachineJwtConfiguration.MachineDecoder;
import ru.hardcode.dashboard.dto.AppErrorResponse;
import ru.hardcode.dashboard.exception.AppError;
import ru.hardcode.dashboard.exception.AppErrors;
import tools.jackson.databind.ObjectMapper;

/**
 * Dedicated stateless bearer chain for ITSM machine intake. It must be evaluated before
 * the unchanged application chain; CSRF is relaxed only for these machine routes.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ItsmIntegrationAuthProperties.class)
public class ItsmIntegrationSecurityConfiguration {

	private static final String INTEGRATION_PATH = "/api/integrations/itsm/**";

	@Bean
	@Order(1)
	public SecurityFilterChain itsmIntegrationSecurityFilterChain(
			HttpSecurity http,
			MachineDecoder machineDecoder,
			ItsmIntegrationAuthProperties properties,
			ObjectMapper objectMapper
	) throws Exception {
		AuthenticationEntryPoint authenticationEntryPoint = machineAuthenticationEntryPoint(objectMapper);
		AccessDeniedHandler accessDeniedHandler = machineAccessDeniedHandler(objectMapper);
		Converter<Jwt, AbstractAuthenticationToken> authenticationConverter =
				machineAuthenticationConverter(properties);

		return http
				.securityMatcher(INTEGRATION_PATH)
				.csrf(csrf -> csrf.ignoringRequestMatchers(INTEGRATION_PATH))
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint(authenticationEntryPoint)
						.accessDeniedHandler(accessDeniedHandler))
				.authorizeHttpRequests(authorize -> authorize
						.anyRequest().hasAuthority(properties.importRole()))
				.oauth2ResourceServer(oauth2 -> oauth2
						.authenticationEntryPoint(authenticationEntryPoint)
						.accessDeniedHandler(accessDeniedHandler)
						.jwt(jwt -> jwt
								.decoder(machineDecoder.decoder())
								.jwtAuthenticationConverter(authenticationConverter)))
				.build();
	}

	private static Converter<Jwt, AbstractAuthenticationToken> machineAuthenticationConverter(
			ItsmIntegrationAuthProperties properties
	) {
		return jwt -> {
			List<GrantedAuthority> authorities = new ArrayList<>();
			String clientId = jwt.getClaimAsString("azp");
			if (properties.isConfigured()
					&& properties.clientId().equals(clientId)
					&& hasImportRole(jwt, clientId, properties.importRole())) {
				authorities.add(new SimpleGrantedAuthority(properties.importRole()));
			}
			String principalName = clientId == null ? jwt.getSubject() : clientId;
			return new JwtAuthenticationToken(jwt, authorities, principalName);
		};
	}

	private static boolean hasImportRole(Jwt jwt, String clientId, String importRole) {
		Map<String, Object> claims = jwt.getClaims();
		return containsRole(claims.get("realm_access"), importRole)
				|| containsClientRole(claims.get("resource_access"), clientId, importRole)
				|| containsRole(claims.get("roles"), importRole);
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

	private static AuthenticationEntryPoint machineAuthenticationEntryPoint(ObjectMapper objectMapper) {
		return (request, response, exception) -> {
			response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"itsm-integration\"");
			writeError(response, objectMapper, AppErrors.ITSM_MACHINE_AUTH_REQUIRED);
		};
	}

	private static AccessDeniedHandler machineAccessDeniedHandler(ObjectMapper objectMapper) {
		return (request, response, exception) ->
				writeError(response, objectMapper, AppErrors.ITSM_MACHINE_ACCESS_DENIED);
	}

	private static void writeError(HttpServletResponse response, ObjectMapper objectMapper, AppError error)
			throws IOException {
		response.setStatus(error.getHttpStatus().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		objectMapper.writeValue(
				response.getOutputStream(),
				new AppErrorResponse(error.getCode(), error.getMessage())
		);
	}
}
