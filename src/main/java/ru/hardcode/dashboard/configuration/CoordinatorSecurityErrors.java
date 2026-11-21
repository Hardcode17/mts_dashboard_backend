package ru.hardcode.dashboard.configuration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import ru.hardcode.dashboard.dto.AppErrorResponse;
import ru.hardcode.dashboard.exception.AppError;
import ru.hardcode.dashboard.exception.AppErrors;
import tools.jackson.databind.ObjectMapper;

@Component
public class CoordinatorSecurityErrors {

	private final ObjectMapper objectMapper;

	public CoordinatorSecurityErrors(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public AuthenticationEntryPoint authenticationEntryPoint() {
		return (request, response, exception) -> {
			response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
			write(response, AppErrors.COORDINATOR_AUTH_REQUIRED);
		};
	}

	public AccessDeniedHandler accessDeniedHandler() {
		return (request, response, exception) -> write(response, AppErrors.COORDINATOR_ACCESS_DENIED);
	}

	private void write(HttpServletResponse response, AppError error) throws IOException {
		response.setStatus(error.getHttpStatus().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		objectMapper.writeValue(response.getOutputStream(), new AppErrorResponse(error.getCode(), error.getMessage()));
	}
}
