package ru.itmo.hhprocess.dto.candidate;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

@Value
@Builder
public class InvitationResponseResponse {
    UUID applicationId;
    String status;
    String message;
}
