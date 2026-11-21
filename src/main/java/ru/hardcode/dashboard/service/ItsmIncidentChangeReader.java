package ru.hardcode.dashboard.service;

import java.util.List;

import ru.hardcode.dashboard.dto.IncidentChangeEvent;
import ru.hardcode.dashboard.dto.IncidentChangesPage;

/**
 * Read contract of the durable INC change journal (KAN-28). This is the internal contract
 * for consumers of INC changes: derive new state (for example the KAN-25 event queue) by
 * paging with {@link #changesAfter} and remembering {@code lastEventSeq} as the cursor.
 *
 * <p>Guarantees:
 * <ul>
 *     <li>{@code eventSeq} is monotonic and unique across all sources and coordinators,
 *         independent of the snapshot change revision. One revision can carry many events.</li>
 *     <li>Events are returned in ascending {@code eventSeq} order. Replaying the same cursor
 *         after a restart or a repeated snapshot delivery returns the same events, with the
 *         same sequence numbers, in the same order; repeated deliveries never add events.</li>
 *     <li>Several events can share one observation time (and one revision); the event
 *         sequence still orders them totally.</li>
 *     <li>A {@code MISSING} fact only records a disappearance. It is not a resolution and it
 *         never deletes or rewrites committed history.</li>
 * </ul>
 */
public interface ItsmIncidentChangeReader {

	/**
	 * Journal events with {@code eventSeq} greater than {@code afterEventSeq}, ascending.
	 *
	 * @param afterEventSeq exclusive cursor; {@code 0} reads from the beginning
	 * @param limit          maximum number of events; {@link IncidentChangesPage#hasMore()}
	 *                       reports whether more follow
	 */
	IncidentChangesPage changesAfter(long afterEventSeq, int limit);

	/**
	 * The full journal of one incident, oldest first, including added and missing facts.
	 * Remains readable after the incident disappeared from the source.
	 */
	List<IncidentChangeEvent> incidentHistory(String incidentId);
}
