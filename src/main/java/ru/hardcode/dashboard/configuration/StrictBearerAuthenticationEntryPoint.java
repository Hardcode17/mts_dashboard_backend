package ru.hardcode.dashboard.configuration;

import java.io.IOException;
import java.util.Enumeration;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import ru.hardcode.dashboard.exception.AppErrors;
import tools.jackson.databind.ObjectMapper;

/**
 * Fail-closed entry point for stateless API chains: only a {@code Bearer} Authorization header
 * is accepted. Missing, malformed, {@code Basic} or {@code Form} credentials get the chain's
 * API error instead of a redirect, browser login or a form challenge. The challenge names the
 * chain's own realm, so human and machine routes stay distinguishable by their {@code 401}.
 */
public final class StrictBearerAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final ObjectMapper objectMapper;
	private final AppErrors error;
	private final String realm;

	public StrictBearerAuthenticationEntryPoint(ObjectMapper objectMapper, AppErrors error, String realm) {
		this.objectMapper = objectMapper;
		this.error = error;
		this.realm = realm;
	}

	@Override
	public void commence(
			HttpServletRequest request,
			HttpServletResponse response,
			AuthenticationException exception
	) throws IOException {
		// KAN-27 contract: the bare realm for missing credentials; a supplied Bearer token that
		// failed validation is reported as invalid_token in the same challenge.
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, isBearerRequest(request)
				? "Bearer realm=\"" + realm + "\", error=\"invalid_token\""
				: "Bearer realm=\"" + realm + "\"");
		ApiErrorWriter.write(response, objectMapper, error);
	}

	private static boolean isBearerRequest(HttpServletRequest request) {
		Enumeration<String> headers = request.getHeaders(HttpHeaders.AUTHORIZATION);
		return headers != null && headers.hasMoreElements()
				&& headers.nextElement().toLowerCase(Locale.ROOT).startsWith("bearer ");
	}
}
