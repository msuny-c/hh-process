package ru.itmo.hhprocess.dto.candidate;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CandidateApplicationResponse {
    UUID applicationId;
    UUID vacancyId;
    String status;
    Instant createdAt;
    Instant updatedAt;
    InvitationInfo invitation;
    InterviewInfo interview;

    @Value
    @Builder
    public static class InvitationInfo {
        String message;
        Instant expiresAt;
    }

    @Value
    @Builder
    public static class InterviewInfo {
        UUID interviewId;
        Instant scheduledAt;
        Integer durationMinutes;
        String status;
    }
}
