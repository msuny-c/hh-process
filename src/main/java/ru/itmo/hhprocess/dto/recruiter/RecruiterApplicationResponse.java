package ru.itmo.hhprocess.dto.recruiter;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecruiterApplicationResponse {
    UUID applicationId;
    UUID vacancyId;
    UUID candidateId;
    String status;
    String resumeText;
    String coverLetter;
    ScreeningInfo screening;
    Instant createdAt;

    @Value
    @Builder
    public static class ScreeningInfo {
        int score;
        boolean passed;
        List<String> matchedSkills;
    }
}
