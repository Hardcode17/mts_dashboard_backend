package ru.hardcode.dashboard.exception;

import java.util.Map;

/**
 * An {@link AppException} that additionally identifies the rejected snapshot record. The
 * public message stays the safe {@link AppErrors#ITSM_INVALID_RECORD} message; the index and
 * field are structured so callers and workers can report them without parsing text.
 */
public class ItsmRecordValidationException extends AppException {

	private final int recordIndex;
	private final String field;

	public ItsmRecordValidationException(int recordIndex, String field, String reason, Throwable cause) {
		super(AppErrors.ITSM_INVALID_RECORD, Map.of("index", recordIndex, "field", field, "reason", reason), cause);
		this.recordIndex = recordIndex;
		this.field = field;
	}

	public int getRecordIndex() {
		return recordIndex;
	}

	public String getField() {
		return field;
	}
}
