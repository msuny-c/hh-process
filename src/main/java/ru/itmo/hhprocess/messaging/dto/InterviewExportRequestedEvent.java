package ru.itmo.hhprocess.messaging.dto;

import java.time.Instant;
import java.util.UUID;

public record InterviewExportRequestedEvent(
        UUID eventId,
        UUID interviewId,
        Instant createdAt
) {
}
