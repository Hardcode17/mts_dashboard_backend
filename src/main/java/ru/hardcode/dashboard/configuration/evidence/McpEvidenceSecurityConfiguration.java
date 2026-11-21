package ru.hardcode.dashboard.configuration.evidence;

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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import ru.hardcode.dashboard.configuration.ItsmMachineJwtConfiguration;
import ru.hardcode.dashboard.dto.AppErrorResponse;
import ru.hardcode.dashboard.exception.AppError;
import ru.hardcode.dashboard.exception.AppErrors;
import tools.jackson.databind.ObjectMapper;

/**
 * Dedicated stateless bearer chain for the read-only MCP evidence transport. It exists only
 * when evidence is enabled and is evaluated before the application chain, which is left
 * unchanged.
 *
 * <p>Every MCP request carries a bearer token issued to the integration machine client and
 * validated against the configured issuer/JWKS, audience, {@code azp} and role. The chain
 * accepts only Streamable HTTP requests on {@code /mcp/evidence}: JSON-RPC {@code POST},
 * the listening {@code GET}, {@code DELETE} and the session {@code OPTIONS}. Other methods
 * and other paths are refused before the transport sees them.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnEvidenceMapping
@EnableConfigurationProperties(McpEvidenceSecurityProperties.class)
public class McpEvidenceSecurityConfiguration {

	public static final String MCP_EVIDENCE_PATH = "/mcp/evidence";
	private static final String MCP_EVIDENCE_MATCHER = MCP_EVIDENCE_PATH + "/**";

	@Bean
	@Order(2)
	SecurityFilterChain mcpEvidenceSecurityFilterChain(
			HttpSecurity http,
			ItsmMachineJwtConfiguration.MachineDecoder machineDecoder,
			McpEvidenceSecurityProperties properties,
			ObjectMapper objectMapper
	) throws Exception {
		AuthenticationEntryPoint authenticationEntryPoint = (request, response, exception) -> {
			response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"mcp-evidence\"");
			writeError(response, objectMapper, AppErrors.ITSM_MACHINE_AUTH_REQUIRED);
		};
		AccessDeniedHandler accessDeniedHandler = (request, response, exception) ->
				writeError(response, objectMapper, AppErrors.MCP_EVIDENCE_ACCESS_DENIED);
		Converter<Jwt, AbstractAuthenticationToken> converter = evidenceClientConverter(properties);
		JwtDecoder decoder = machineDecoder.decoder();

		return http
				.securityMatcher(MCP_EVIDENCE_PATH, MCP_EVIDENCE_MATCHER)
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint(authenticationEntryPoint)
						.accessDeniedHandler(accessDeniedHandler))
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers(HttpMethod.POST, MCP_EVIDENCE_PATH).hasAuthority(properties.role())
						.requestMatchers(HttpMethod.GET, MCP_EVIDENCE_PATH).hasAuthority(properties.role())
						.requestMatchers(HttpMethod.DELETE, MCP_EVIDENCE_PATH).hasAuthority(properties.role())
						.anyRequest().denyAll())
				.oauth2ResourceServer(oauth2 -> oauth2
						.authenticationEntryPoint(authenticationEntryPoint)
						.accessDeniedHandler(accessDeniedHandler)
						.jwt(jwt -> jwt
								.decoder(decoder)
								.jwtAuthenticationConverter(converter)))
				.build();
	}

	private static Converter<Jwt, AbstractAuthenticationToken> evidenceClientConverter(
			McpEvidenceSecurityProperties properties
	) {
		return jwt -> {
			List<GrantedAuthority> authorities = new ArrayList<>();
			String clientId = jwt.getClaimAsString("azp");
			if (properties.isConfigured()
					&& jwt.getAudience().contains(properties.audience())
					&& properties.clientId().equals(clientId)
					&& hasRole(jwt, clientId, properties.role())) {
				authorities.add(new SimpleGrantedAuthority(properties.role()));
			}
			String principalName = clientId == null ? jwt.getSubject() : clientId;
			return new JwtAuthenticationToken(jwt, authorities, principalName);
		};
	}

	private static boolean hasRole(Jwt jwt, String clientId, String role) {
		Map<String, Object> claims = jwt.getClaims();
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

	private static void writeError(HttpServletResponse response, ObjectMapper objectMapper, AppError error)
			throws IOException {
		response.setStatus(error.getHttpStatus().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		objectMapper.writeValue(response.getOutputStream(), new AppErrorResponse(error.getCode(), error.getMessage()));
	}
}
