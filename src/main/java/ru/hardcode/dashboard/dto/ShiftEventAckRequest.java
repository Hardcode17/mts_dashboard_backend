package ru.hardcode.dashboard.dto;

import java.util.List;

/**
 * Body of the group «Увидел» (KAN-29): the exact set of shown {@code eventId} values. Only the
 * alarm events ({@code ackRequired = true}) can be confirmed; the request is atomic and a
 * repeat of the same set changes nothing.
 *
 * @param eventIds 1-1000 unique {@code evt-} identifiers
 */
public record ShiftEventAckRequest(List<String> eventIds) {
}
