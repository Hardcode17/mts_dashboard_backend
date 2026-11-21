package ru.hardcode.dashboard.dto;

import java.util.List;

/**
 * One cursor page of the INC change journal (KAN-28).
 *
 * @param events      up to {@code limit} events with {@code eventSeq} greater than the requested
 *                    cursor, in ascending {@code eventSeq} order
 * @param hasMore     whether more events follow beyond this page
 * @param lastEventSeq the highest {@code eventSeq} in this page, or the requested cursor when the
 *                    page is empty; pass it back as the next cursor
 */
public record IncidentChangesPage(List<IncidentChangeEvent> events, boolean hasMore, long lastEventSeq) {
}
