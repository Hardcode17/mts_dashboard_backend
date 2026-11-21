package ru.hardcode.dashboard.dto;

import java.util.List;

/**
 * Result of the group «Увидел» (KAN-29). {@code confirmedEventIds} echoes the acknowledged
 * set; {@code events} is the remaining queue in the same shape as
 * {@code GET /api/dashboard/shift/events}, so the common screen can immediately show what is
 * still unconfirmed. The author and time of the first acknowledgement live in
 * {@code shift_event.acknowledged_by/at} for the screen and are never overwritten by repeats.
 *
 * @param status            always {@code acknowledged} for an accepted set
 * @param confirmedEventIds  every requested {@code eventId}, in the request order
 * @param events            the remaining (not yet acknowledged) queue events
 * @param nextEventSeq      cursor of the remaining queue, as in the queue read
 */
public record ShiftEventAckResponse(
		String status,
		List<String> confirmedEventIds,
		List<ShiftEventResponse> events,
		long nextEventSeq
) {
}
