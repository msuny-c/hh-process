package com.example.hhprocess.dto;

import com.example.hhprocess.enums.VacancyStatus;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class VacancyResponse {
    Long id;
    String title;
    String description;
    VacancyStatus status;
    LocalDateTime createdAt;
}
