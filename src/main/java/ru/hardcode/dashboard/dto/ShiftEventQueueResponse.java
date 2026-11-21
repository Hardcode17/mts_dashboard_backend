package ru.hardcode.dashboard.dto;

import java.util.List;

/**
 * One page of the shift event queue (KAN-25). {@code nextEventSeq} is the cursor of the next
 * poll; it equals the previous {@code sinceEventSeq} when nothing new arrived.
 */
public record ShiftEventQueueResponse(List<ShiftEventResponse> events, long nextEventSeq) {
}
