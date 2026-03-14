package ru.itmo.hhprocess.dto.common;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
@Builder
public class NotificationResponse {
    UUID id;
    UUID applicationId;
    String type;
    String message;
    boolean read;
    Instant createdAt;
}
