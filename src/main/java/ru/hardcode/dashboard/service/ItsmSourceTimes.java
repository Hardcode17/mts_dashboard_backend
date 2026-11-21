package ru.hardcode.dashboard.service;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * The optional source-side time of an ITSM record (KAN-28). The field is not required or
 * validated by the snapshot contract, so an absent or unparseable value simply means the
 * source time is unknown; the observation time is tracked separately.
 */
public final class ItsmSourceTimes {

	private ItsmSourceTimes() {
	}

	/**
	 * @return the parsed instant, or {@code null} when the value is absent or unparseable
	 */
	public static Instant of(Object value) {
		if (value instanceof String text && !text.isBlank()) {
			try {
				return Instant.parse(text);
			} catch (DateTimeParseException ignored) {
				// The source time stays unknown.
			}
		}
		return null;
	}
}
