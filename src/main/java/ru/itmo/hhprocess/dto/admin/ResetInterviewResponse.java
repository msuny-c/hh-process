package ru.itmo.hhprocess.dto.admin;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

@Value
@Builder
public class ResetInterviewResponse {
    UUID interviewId;
    UUID applicationId;
    String status;
    String message;
}
