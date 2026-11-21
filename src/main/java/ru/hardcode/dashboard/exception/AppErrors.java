package ru.hardcode.dashboard.exception;

import org.springframework.http.HttpStatus;

public enum AppErrors implements AppError {
	RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND", HttpStatus.NOT_FOUND, "Resource with id ${id} not found"),
	REQUEST_BODY_TOO_LARGE("REQUEST_BODY_TOO_LARGE", HttpStatus.CONTENT_TOO_LARGE, "Request body exceeds ${maxBytes} bytes"),
	INVALID_REQUEST_BODY("INVALID_REQUEST_BODY", HttpStatus.BAD_REQUEST, "Request body is malformed or missing"),
	UNSUPPORTED_MEDIA_TYPE("UNSUPPORTED_MEDIA_TYPE", HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Content type is not supported"),
	ITSM_INVALID_IDEMPOTENCY_KEY("ITSM_INVALID_IDEMPOTENCY_KEY", HttpStatus.BAD_REQUEST, "Idempotency-Key must be a UUID"),
	ITSM_INVALID_SOURCE_ID("ITSM_INVALID_SOURCE_ID", HttpStatus.BAD_REQUEST, "X-Source-Id must be 1-128 visible ASCII characters"),
	ITSM_INVALID_SOURCE_VERSION("ITSM_INVALID_SOURCE_VERSION", HttpStatus.BAD_REQUEST, "X-Source-Version must be a positive 64-bit integer"),
	ITSM_INVALID_PAYLOAD("ITSM_INVALID_PAYLOAD", HttpStatus.BAD_REQUEST, "Request body must be a single JSON array"),
	ITSM_INVALID_RECORD("ITSM_INVALID_RECORD", HttpStatus.BAD_REQUEST,
			"ITSM record in snapshot at index ${index} has invalid field ${field}: ${reason}"),
	ITSM_DUPLICATE_RECORD("ITSM_DUPLICATE_RECORD", HttpStatus.BAD_REQUEST,
			"ITSM snapshot contains duplicate id; record at index ${index} repeats index ${firstIndex}"),
	ITSM_IDEMPOTENCY_CONFLICT("ITSM_IDEMPOTENCY_CONFLICT", HttpStatus.CONFLICT, "Idempotency-Key was already used with different request metadata or payload"),
	ITSM_SOURCE_VERSION_CONFLICT("ITSM_SOURCE_VERSION_CONFLICT", HttpStatus.CONFLICT, "Source version was already accepted with a different idempotency key"),
	ITSM_INTAKE_QUEUE_FULL("ITSM_INTAKE_QUEUE_FULL", HttpStatus.TOO_MANY_REQUESTS, "ITSM intake queue is full (${max} outstanding batches)"),
	ITSM_BATCH_NOT_FOUND("ITSM_BATCH_NOT_FOUND", HttpStatus.NOT_FOUND, "ITSM batch ${batchId} not found"),
	ITSM_MACHINE_AUTH_REQUIRED("ITSM_MACHINE_AUTH_REQUIRED", HttpStatus.UNAUTHORIZED, "Machine bearer token is required"),
	ITSM_MACHINE_ACCESS_DENIED("ITSM_MACHINE_ACCESS_DENIED", HttpStatus.FORBIDDEN, "Machine client identity or itsm import permission is missing"),
	HUMAN_AUTH_REQUIRED("HUMAN_AUTH_REQUIRED", HttpStatus.UNAUTHORIZED, "Human access token is required"),
	HUMAN_ACCESS_DENIED("HUMAN_ACCESS_DENIED", HttpStatus.FORBIDDEN, "The access token does not grant access to this resource");

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
