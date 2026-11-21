package ru.hardcode.dashboard.dto;

import java.time.Instant;
import java.util.UUID;

public record CoordinationWindowResponse(UUID windowId, Instant expiresAt) {
}
