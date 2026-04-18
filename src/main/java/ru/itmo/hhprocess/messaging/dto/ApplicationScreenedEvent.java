package ru.itmo.hhprocess.messaging.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ApplicationScreenedEvent(
        UUID eventId,
        UUID applicationId,
        boolean passed,
        int score,
        List<String> matchedSkills,
        Map<String, Object> detailsJson,
        Instant screeningStartedAt,
        Instant processedAt
) {
}
