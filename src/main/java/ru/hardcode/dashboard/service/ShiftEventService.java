package ru.hardcode.dashboard.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.hardcode.dashboard.dto.ShiftEventQueueResponse;
import ru.hardcode.dashboard.dto.ShiftEventResponse;
import ru.hardcode.dashboard.repository.ShiftEventRepository;
import ru.hardcode.dashboard.repository.ShiftEventRepository.StoredEvent;
import ru.hardcode.dashboard.service.ItsmIncidentChangeFeed.IncidentChange;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * KAN-25 formation of the significant shift event queue. Detection is cursor-driven over the
 * durable INC journal (see {@link ItsmIncidentChangeFeed}): every call folds in the changes
 * after the stored cursor, so a replayed package, a restart or a change applied by the
 * coordinator edit path is handled identically.
 *
 * <p>eventId contract (consumed by KAN-29 «Увидел» and KAN-35 notifications):
 * <ul>
 * <li>one journaled change of one INC produces at most one event; every reason matched by
 * that single update is merged into its {@code reasons} array;</li>
 * <li>eventId is {@code evt-} plus at least four digits, assigned once from a database
 * sequence (gaps are possible and harmless);</li>
 * <li>a repeat of the same revision never adds or changes anything — the event is
 * deduplicated by (source, incident, change revision) and keeps its original eventId;</li>
 * <li>a later change of the same INC (for example a reopening) is a new revision and gets a
 * new eventId;</li>
 * <li>{@code ackRequired} is true exactly for the alarm reasons (new active P4, escalation
 * to P4, reopening of a critical); those events require «Увидел», the queue itself does not
 * confirm anything.</li>
 * </ul>
 */
@Service
public class ShiftEventService {

	private static final TypeReference<Map<String, Object>> PAYLOAD = new TypeReference<>() {};

	/** Journal rows folded in by one processing pass; the cursor makes the batching safe. */
	private static final int PROCESS_BATCH = 500;

	private final ItsmIncidentChangeFeed feed;
	private final ShiftEventRepository repository;
	private final ObjectMapper json;

	public ShiftEventService(ItsmIncidentChangeFeed feed, ShiftEventRepository repository, ObjectMapper json) {
		this.feed = feed;
		this.repository = repository;
		this.json = json;
	}

	/**
	 * Processes the journal after the stored cursor into queue events. Idempotent: the
	 * baseline rows of a source are skipped, and a change already recorded for the same
	 * revision keeps its event untouched.
	 *
	 * @return the number of new queue events
	 */
	@Transactional
	public int processPending() {
		long lastRevision = repository.lockProgress();
		List<IncidentChange> changes = feed.afterRevision(lastRevision, PROCESS_BATCH);
		int created = 0;
		for (IncidentChange change : changes) {
			// The first journaled change of a source is its baseline import: the shift queue
			// starts from that known state instead of flooding it with signals.
			long baseline = repository.ensureBaseline(change.sourceId(), change.revision());
			if (change.revision() <= baseline) {
				continue;
			}
			List<ShiftEventReason> reasons = ShiftEventDetector.reasons(change);
			if (reasons.isEmpty()) {
				continue;
			}
			if (repository.insertEvent(change.sourceId(), change.sourceVersion(), change.incidentId(),
					change.revision(), reasons, ShiftEventDetector.requiresAcknowledgement(reasons),
					json.writeValueAsString(ShiftEventDetector.notificationPayload(change.after())))) {
				created++;
			}
		}
		if (!changes.isEmpty()) {
			repository.advanceProgress(changes.getLast().revision());
		}
		return created;
	}

	/**
	 * Reads the queue after {@code sinceEventSeq} in ascending {@code eventSeq} order.
	 * {@code nextEventSeq} of the page is the cursor of the next poll; it stays at
	 * {@code sinceEventSeq} when nothing new arrived.
	 */
	@Transactional(readOnly = true)
	public QueuePage events(long sinceEventSeq, int limit) {
		List<StoredEvent> rows = repository.listEvents(sinceEventSeq, limit);
		long nextEventSeq = rows.isEmpty() ? sinceEventSeq : rows.getLast().eventSeq();
		return new QueuePage(new ShiftEventQueueResponse(
				rows.stream().map(row -> new ShiftEventResponse(row.eventId(), row.eventSeq(), row.incidentId(),
						row.reasons(), row.ackRequired(), row.changeRevision(), row.sourceId(), row.createdAt(),
						json.readValue(row.payload(), PAYLOAD))).toList(),
				nextEventSeq), nextEventSeq);
	}

	/** One page of the queue: the HTTP body plus the cursor for the next poll. */
	public record QueuePage(ShiftEventQueueResponse body, long nextEventSeq) {
	}
}
