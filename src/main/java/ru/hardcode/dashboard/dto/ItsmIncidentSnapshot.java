package ru.hardcode.dashboard.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The complete validated result of parsing one original ITSM REST JSON array. It is either
 * the whole snapshot or it does not exist: any invalid or duplicate record rejects the batch.
 * Records keep their original order; IDs are unique.
 */
public record ItsmIncidentSnapshot(List<ItsmIncidentRecord> records) {

	public ItsmIncidentSnapshot {
		Objects.requireNonNull(records, "records");
		var byId = new LinkedHashMap<String, ItsmIncidentRecord>();
		for (ItsmIncidentRecord record : records) {
			Objects.requireNonNull(record, "record");
			if (byId.putIfAbsent(record.id(), record) != null) {
				throw new IllegalArgumentException("duplicate incident ID");
			}
		}
		records = List.copyOf(records);
	}

	public int size() {
		return records.size();
	}

	public boolean isEmpty() {
		return records.isEmpty();
	}

	public Set<String> ids() {
		return byId().keySet();
	}

	public Optional<ItsmIncidentRecord> find(String id) {
		if (id == null) {
			return Optional.empty();
		}
		return records.stream().filter(record -> record.id().equals(id)).findFirst();
	}

	/**
	 * Records keyed by ID in original order. Each call builds a fresh unmodifiable map, so
	 * the snapshot itself keeps no mutable state.
	 */
	public Map<String, ItsmIncidentRecord> byId() {
		var byId = new LinkedHashMap<String, ItsmIncidentRecord>();
		records.forEach(record -> byId.put(record.id(), record));
		return Collections.unmodifiableMap(byId);
	}
}
