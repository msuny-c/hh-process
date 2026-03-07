package com.example.hhprocess.dto;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class CandidateResponse {
    Long id;
    String fullName;
    String email;
    String phone;
    String resumeText;
    LocalDateTime createdAt;
}
