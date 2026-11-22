package ru.hardcode.dashboard.dto;

import java.time.Instant;

/**
 * A saved shift note (KAN-43). The author always comes from the authenticated local identity,
 * never from the request. {@code eventSeq} is the position of the note event in the shared
 * KAN-28 chronology; the published snapshot {@code revision} is not advanced by notes.
 *
 * @param noteId            stable id of the note; doubles as the writer deduplication key
 * @param incidentId        the INC the note belongs to
 * @param authorId          local coordinator id of the author
 * @param authorLogin       login of the author
 * @param authorDisplayName display name of the author
 * @param observedAt        the observation time carried by the note
 * @param eventSeq          shared chronology position of the note event
 * @param text              the note text
 */
public record IncidentNoteResponse(String noteId, String incidentId, long authorId, String authorLogin,
		String authorDisplayName, Instant observedAt, long eventSeq, String text) {
}
