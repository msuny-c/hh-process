package ru.itmo.hhprocess.dto.recruiter;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

@Value
@Builder
public class RejectResponse {
    UUID applicationId;
    String status;
}
