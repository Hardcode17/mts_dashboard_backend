package ru.hardcode.dashboard.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.hardcode.dashboard.dto.AppErrorResponse;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(AppException.class)
	public ResponseEntity<AppErrorResponse> handleAppException(AppException exception) {
		AppError error = exception.getAppError();
		return ResponseEntity.status(error.getHttpStatus())
				.body(new AppErrorResponse(error.getCode(), exception.getMessage()));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<AppErrorResponse> handleUnreadableBody() {
		return errorResponse(AppErrors.INVALID_REQUEST_BODY);
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<AppErrorResponse> handleUnsupportedMediaType() {
		return errorResponse(AppErrors.UNSUPPORTED_MEDIA_TYPE);
	}

	private static ResponseEntity<AppErrorResponse> errorResponse(AppError error) {
		return ResponseEntity.status(error.getHttpStatus())
				.body(new AppErrorResponse(error.getCode(), error.getMessage()));
	}
}
