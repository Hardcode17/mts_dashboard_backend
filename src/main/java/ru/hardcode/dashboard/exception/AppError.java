package ru.hardcode.dashboard.exception;

import org.springframework.http.HttpStatus;

public interface AppError {

	String getCode();

	HttpStatus getHttpStatus();

	String getMessage();
}
