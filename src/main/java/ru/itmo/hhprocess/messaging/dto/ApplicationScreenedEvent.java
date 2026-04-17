package ru.itmo.hhprocess.messaging.dto;

import java.time.Instant;
import java.util.UUID;

public record ApplicationScreenedEvent(
        UUID eventId,
        UUID applicationId,
        boolean passed,
        int score,
        Instant processedAt
) {
}
