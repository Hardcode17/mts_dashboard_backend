package ru.hardcode.dashboard.configuration;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * Browser origins allowed to call the human API (KAN-64). The defaults are the development
 * origins of the SPA registered as redirect URIs of the {@code dashboard-web} client. An
 * explicitly configured empty value disables CORS entirely (fail-closed for every origin).
 */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(List<String> allowedOrigins) {

	/** Development origins of the frontend: the Vite dev server and the local build. */
	public static final List<String> DEFAULT_ALLOWED_ORIGINS = List.of(
			"http://localhost:3000", "http://localhost:5173");

	public CorsProperties {
		allowedOrigins = normalize(allowedOrigins);
	}

	private static List<String> normalize(List<String> origins) {
		if (origins == null) {
			return DEFAULT_ALLOWED_ORIGINS;
		}
		// Browsers never send a trailing slash in the Origin header; forgive it in config.
		List<String> normalized = origins.stream()
				.map(origin -> origin == null ? "" : origin.strip())
				.filter(StringUtils::hasText)
				.map(origin -> origin.replaceAll("/+$", ""))
				.toList();
		return List.copyOf(normalized);
	}
}
