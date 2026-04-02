package ru.itmo.hhprocess.dto.recruiter;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

@Value
@Builder
public class InterviewActionResponse {
    UUID interviewId;
    UUID applicationId;
    String status;
    String message;
}
