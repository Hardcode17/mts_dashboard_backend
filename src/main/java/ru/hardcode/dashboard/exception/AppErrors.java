package ru.hardcode.dashboard.exception;

import org.springframework.http.HttpStatus;

public enum AppErrors implements AppError {
	RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND", HttpStatus.NOT_FOUND, "Resource with id ${id} not found"),
	REQUEST_BODY_TOO_LARGE("REQUEST_BODY_TOO_LARGE", HttpStatus.CONTENT_TOO_LARGE, "Request body exceeds ${maxBytes} bytes");

	private final String code;
	private final HttpStatus httpStatus;
	private final String message;

	AppErrors(String code, HttpStatus httpStatus, String message) {
		this.code = code;
		this.httpStatus = httpStatus;
		this.message = message;
	}

	@Override
	public String getCode() {
		return code;
	}

	@Override
	public HttpStatus getHttpStatus() {
		return httpStatus;
	}

	@Override
	public String getMessage() {
		return message;
	}
}
