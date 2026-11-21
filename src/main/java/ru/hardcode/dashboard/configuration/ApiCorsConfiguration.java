package ru.hardcode.dashboard.configuration;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * CORS for the human API used by the browser SPA (KAN-64). The configuration is wired into
 * the human security chain only: the machine intake chain {@code /api/integrations/itsm/**}
 * keeps its own policy without CORS, so nothing browser-facing is opened there.
 *
 * <p>The {@link CorsConfigurationSource} is deliberately not a public bean: Boot's web MVC
 * auto-configuration would register a global servlet {@code CorsFilter} from such a bean
 * and that filter would also cover machine-only routes.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CorsProperties.class)
public class ApiCorsConfiguration {

	/** Preflight responses may be cached by the browser for an hour. */
	static final long PREFLIGHT_MAX_AGE_SECONDS = 3600;

	static CorsConfigurationSource apiCorsConfigurationSource(CorsProperties properties) {
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		// /api/integrations/itsm/** never reaches this source: the machine chain is
		// evaluated first (ItsmIntegrationSecurityConfiguration, order 1).
		source.registerCorsConfiguration("/api/**", apiCorsConfiguration(properties));
		return source;
	}

	static CorsConfiguration apiCorsConfiguration(CorsProperties properties) {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(properties.allowedOrigins());
		configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
		configuration.setAllowedHeaders(List.of(
				HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE,
				HttpHeaders.IF_NONE_MATCH, "X-Request-Id"));
		configuration.setExposedHeaders(List.of(HttpHeaders.ETAG, HttpHeaders.LOCATION, "X-Request-Id"));
		// The SPA authenticates with a Bearer token; cookies are never required cross-origin.
		configuration.setAllowCredentials(false);
		configuration.setMaxAge(PREFLIGHT_MAX_AGE_SECONDS);
		return configuration;
	}
}
