package ru.hardcode.dashboard.exception;

import org.springframework.http.ResponseEntity;
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
}
