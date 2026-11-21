package ru.hardcode.dashboard.configuration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import ru.hardcode.dashboard.dto.AppErrorResponse;
import ru.hardcode.dashboard.exception.AppError;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes the same {@link AppErrorResponse} body that {@code GlobalExceptionHandler} returns for
 * application errors. Security filters run before MVC, so they must write the error themselves.
 */
public final class ApiErrorWriter {

	private ApiErrorWriter() {
	}

	public static void write(
			HttpServletResponse response,
			ObjectMapper objectMapper,
			AppError error
	) throws IOException {
		response.setStatus(error.getHttpStatus().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		objectMapper.writeValue(
				response.getOutputStream(),
				new AppErrorResponse(error.getCode(), error.getMessage())
		);
	}
}
