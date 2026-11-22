package ru.hardcode.dashboard.dto;

import java.util.List;

/**
 * One cursor page of the note journal of an INC (KAN-43).
 *
 * @param incidentId   the INC whose notes are returned
 * @param notes        up to {@code limit} notes with {@code eventSeq} greater than the
 *                     requested cursor, in ascending {@code eventSeq} order
 * @param hasMore      whether more notes follow beyond this page
 * @param lastEventSeq the highest note {@code eventSeq} in this page, or the requested cursor
 *                     when the page is empty; pass it back as the next cursor
 */
public record IncidentNotesPage(String incidentId, List<IncidentNoteResponse> notes, boolean hasMore,
		long lastEventSeq) {
}
