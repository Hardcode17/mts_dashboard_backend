package ru.hardcode.dashboard.exception;

import java.util.Map;

public class AppException extends RuntimeException {

	private final AppError appError;

	public AppException(AppError appError) {
		super(appError.getMessage());
		this.appError = appError;
	}

	public AppException(AppError appError, Throwable cause) {
		super(appError.getMessage(), cause);
		this.appError = appError;
	}

	public AppException(AppError appError, Map<String, ?> parameters) {
		super(formatMessage(appError.getMessage(), parameters));
		this.appError = appError;
	}

	public AppError getAppError() {
		return appError;
	}

	private static String formatMessage(String template, Map<String, ?> parameters) {
		String message = template;
		for (Map.Entry<String, ?> entry : parameters.entrySet()) {
			message = message.replace("${" + entry.getKey() + "}", String.valueOf(entry.getValue()));
		}
		return message;
	}
}
