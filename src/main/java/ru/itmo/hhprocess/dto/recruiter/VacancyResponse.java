package ru.itmo.hhprocess.dto.recruiter;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Value
@Builder
public class VacancyResponse {
    UUID id;
    String title;
    String description;
    String status;
    List<String> requiredSkills;
    int screeningThreshold;
    Instant createdAt;
    Instant updatedAt;
}
