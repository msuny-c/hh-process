package ru.itmo.hhprocess.messaging.dto;

import java.time.Instant;
import java.util.UUID;

public record NotificationRequestedEvent(
        UUID eventId,
        UUID userId,
        UUID applicationId,
        String type,
        String message,
        Instant createdAt
) {
}
