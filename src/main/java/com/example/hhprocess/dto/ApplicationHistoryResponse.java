package com.example.hhprocess.dto;

import com.example.hhprocess.enums.ApplicationStatus;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class ApplicationHistoryResponse {
    Long id;
    ApplicationStatus oldStatus;
    ApplicationStatus newStatus;
    String changedBy;
    String comment;
    LocalDateTime changedAt;
}
