package ru.hardcode.dashboard.service;

/**
 * A channel attempt failed. The reason is a short sanitized code (optionally with an HTTP
 * status number) that is safe to persist and to show to the recipient; it must never carry
 * addresses, tokens, URLs or raw exception text.
 */
public class NotificationChannelFailure extends RuntimeException {

	private final String reason;

	public NotificationChannelFailure(String reason) {
		super(reason);
		this.reason = reason;
	}

	public NotificationChannelFailure(String reason, Throwable cause) {
		super(reason, cause);
		this.reason = reason;
	}

	public String reason() {
		return reason;
	}
}
