package ru.hardcode.dashboard.configuration.filter;

import java.util.Map;

import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.AppException;

final class RequestBodyTooLargeException extends AppException {

	RequestBodyTooLargeException(int maxBytes) {
		super(AppErrors.REQUEST_BODY_TOO_LARGE, Map.of("maxBytes", maxBytes));
	}
}
