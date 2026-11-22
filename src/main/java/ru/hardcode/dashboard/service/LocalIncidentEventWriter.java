package ru.hardcode.dashboard.service;

import java.time.Instant;
import java.util.Map;
import java.util.OptionalLong;

public interface LocalIncidentEventWriter {

	/**
	 * Append in the transaction that writes the business record. A repeat of the same
	 * incident, kind and key returns empty without publishing a second event.
	 */
	OptionalLong append(String incidentId, String kind, String deduplicationKey, Instant observedAt,
			Map<String, Object> details);
}
