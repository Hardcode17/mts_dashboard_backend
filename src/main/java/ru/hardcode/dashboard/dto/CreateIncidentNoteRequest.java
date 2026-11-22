package ru.hardcode.dashboard.dto;

import java.time.Instant;

/**
 * One coordinator shift note for a specific INC (KAN-43).
 *
 * @param text       the note text, 1-4000 characters with at least one non-whitespace one
 * @param observedAt optional observation time; the current time when omitted
 * @param authorId   optional echo of the local coordinator id; when present it must match the
 *                   authenticated coordinator — a foreign author is rejected without writing
 *                   anything
 */
public record CreateIncidentNoteRequest(String text, Instant observedAt, Long authorId) {
}
