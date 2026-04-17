package ru.itmo.hhprocess.messaging.dto;

import java.time.Instant;
import java.util.UUID;

public record ApplicationSubmittedEvent(
        UUID eventId,
        UUID applicationId,
        UUID vacancyId,
        UUID candidateId,
        Instant createdAt
) {
}
