package ru.hardcode.dashboard.service;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.hardcode.dashboard.dto.ShiftEventQueueResponse;
import ru.hardcode.dashboard.dto.ShiftEventResponse;
import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.AppException;
import ru.hardcode.dashboard.repository.ShiftEventRepository;
import ru.hardcode.dashboard.repository.ShiftEventRepository.AckState;
import ru.hardcode.dashboard.repository.ShiftEventRepository.StoredEvent;
import ru.hardcode.dashboard.service.ItsmIncidentChangeFeed.IncidentChange;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * KAN-25 formation of the significant shift event queue and KAN-29 group «Увидел».
 * Detection is cursor-driven over the durable INC journal (see {@link ItsmIncidentChangeFeed}):
 * every call folds in the changes after the stored cursor, so a replayed package, a restart or
 * a change applied by the coordinator edit path is handled identically.
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
 * new eventId, so a new critical episode of the same INC is acknowledged separately;</li>
 * <li>{@code ackRequired} is true exactly for the alarm reasons (new active P4, escalation
 * to P4, reopening of a critical); those events require «Увидел».</li>
 * </ul>
 *
 * <p>The queue read returns the <em>remaining</em> events: rows without an acknowledgement.
 * «Увидел» (KAN-29) is an idempotent group operation over the exact set of shown eventIds —
 * an unknown id or an event that requires no acknowledgement rejects the whole request
 * atomically, and a repeat never changes the author or the time of the first
 * acknowledgement. Ack never closes an internal coordination task (the server rule of
 * KAN-32). The review window of the common screen is a client-side policy: the server
 * accepts the exact set regardless of when the events were shown.
 */
@Service
public class ShiftEventService {

	private static final TypeReference<Map<String, Object>> PAYLOAD = new TypeReference<>() {};

	/** Journal rows folded in by one processing pass; the cursor makes the batching safe. */
	private static final int PROCESS_BATCH = 500;

	/** The acknowledgement response returns the whole remaining queue, capped as the read API. */
	private static final int ACK_QUEUE_LIMIT = 500;

	private static final Pattern EVENT_ID = Pattern.compile("evt-[0-9]{4,}");

	private final ItsmIncidentChangeFeed feed;
	private final ShiftEventRepository repository;
	private final ObjectMapper json;
	private final Clock clock;

	public ShiftEventService(ItsmIncidentChangeFeed feed, ShiftEventRepository repository, ObjectMapper json,
			Clock clock) {
		this.feed = feed;
		this.repository = repository;
		this.json = json;
		this.clock = clock;
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
	 * Reads the remaining queue after {@code sinceEventSeq} in ascending {@code eventSeq}
	 * order: events without an acknowledgement, so confirmed alarm events disappear from the
	 * common screen. {@code nextEventSeq} of the page is the cursor of the next poll; it stays
	 * at {@code sinceEventSeq} when nothing new arrived.
	 */
	@Transactional(readOnly = true)
	public QueuePage events(long sinceEventSeq, int limit) {
		List<StoredEvent> rows = repository.listEvents(sinceEventSeq, limit);
		long nextEventSeq = rows.isEmpty() ? sinceEventSeq : rows.getLast().eventSeq();
		return new QueuePage(new ShiftEventQueueResponse(toResponses(rows), nextEventSeq), nextEventSeq);
	}

	/**
	 * Group «Увидел» (KAN-29) over the exact set of shown eventIds. Atomic and idempotent:
	 * every id must name an existing alarm event ({@code ackRequired = true}), otherwise the
	 * whole request is rejected and nothing is acknowledged. A repeat keeps the author and
	 * the time of the first acknowledgement and never touches the events that arrived later —
	 * the set is explicit, so a newly arrived event stays unconfirmed. Ack never closes an
	 * internal coordination task (KAN-32).
	 *
	 * @param actorUserId local coordinator that pressed «Увидел»
	 * @param eventIds    the shown set, 1-1000 unique {@code evt-} identifiers
	 * @return the confirmed echo plus the remaining queue in the shape of the queue read
	 */
	@Transactional
	public AckResult ack(long actorUserId, List<String> eventIds) {
		validateEventIds(eventIds);
		Map<String, AckState> states = repository.findAckState(eventIds);
		for (String eventId : eventIds) {
			AckState state = states.get(eventId);
			if (state == null) {
				throw new AppException(AppErrors.ACK_EVENT_UNKNOWN, Map.of("eventId", eventId));
			}
			if (!state.ackRequired()) {
				throw new AppException(AppErrors.ACK_EVENT_NOT_ALARM, Map.of("eventId", eventId));
			}
		}
		var acknowledgedAt = clock.instant();
		for (String eventId : eventIds) {
			// Only the first acknowledgement writes; repeats update nothing.
			repository.ackEvent(eventId, actorUserId, acknowledgedAt);
		}
		List<StoredEvent> remaining = repository.listEvents(0, ACK_QUEUE_LIMIT);
		long nextEventSeq = remaining.isEmpty() ? 0 : remaining.getLast().eventSeq();
		return new AckResult(eventIds, nextEventSeq, toResponses(remaining));
	}

	private static void validateEventIds(List<String> eventIds) {
		if (eventIds == null || eventIds.isEmpty() || eventIds.size() > 1000
				|| eventIds.stream().anyMatch(id -> id == null || !EVENT_ID.matcher(id).matches())
				|| new HashSet<>(eventIds).size() != eventIds.size()) {
			throw new AppException(AppErrors.ACK_EVENTS_INVALID);
		}
	}

	private List<ShiftEventResponse> toResponses(List<StoredEvent> rows) {
		return rows.stream().map(row -> new ShiftEventResponse(row.eventId(), row.eventSeq(), row.incidentId(),
				row.reasons(), row.ackRequired(), row.changeRevision(), row.sourceId(), row.createdAt(),
				json.readValue(row.payload(), PAYLOAD))).toList();
	}

	/** One page of the queue: the HTTP body plus the cursor for the next poll. */
	public record QueuePage(ShiftEventQueueResponse body, long nextEventSeq) {
	}

	/**
	 * One group acknowledgement: the echoed ids, the cursor of the remaining queue and the
	 * remaining queue events themselves.
	 */
	public record AckResult(List<String> confirmedEventIds, long nextEventSeq, List<ShiftEventResponse> events) {
	}
}
