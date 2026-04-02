package ru.itmo.hhprocess.dto.recruiter;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
@Builder
public class InviteResponse {
    UUID applicationId;
    String status;
    Instant expiresAt;
    UUID interviewId;
    Instant scheduledAt;
    Integer durationMinutes;
    UUID scheduleSlotId;
}
