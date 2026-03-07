package com.example.hhprocess.dto;

import com.example.hhprocess.enums.ApplicationStatus;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class ApplicationResponse {
    Long id;
    Long vacancyId;
    String vacancyTitle;
    Long candidateId;
    String candidateFullName;
    String candidateEmail;
    String coverLetter;
    ApplicationStatus status;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}
