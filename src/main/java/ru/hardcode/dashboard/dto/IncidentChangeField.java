package ru.hardcode.dashboard.dto;

/**
 * One tracked field of a journaled INC change (KAN-28): {@code previous} is the value before
 * the observation and {@code current} the value after it. Either side is {@code null} when the
 * incident was not observed on that side (an added incident has no previous value, a missing
 * one has no current value).
 */
public record IncidentChangeField(String field, Object previous, Object current) {
}
