package ru.itmo.hhprocess.camunda;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.dto.recruiter.WeekScheduleResponse;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.service.InterviewService;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class CamundaPayloadMapper {

    private static final int UI_PAYLOAD_MAX_LENGTH = 3500;

    private final ObjectMapper objectMapper;
    private final InterviewService interviewService;

    public Map<String, Object> applicationSummary(ApplicationEntity application) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applicationId", application.getId());
        result.put("vacancyId", application.getVacancy().getId());
        result.put("vacancyTitle", application.getVacancy().getTitle());
        result.put("candidateId", application.getCandidateUser().getId());
        result.put("candidateEmail", application.getCandidateUser().getEmail());
        result.put("status", application.getStatus().name());
        result.put("resumeText", application.getResumeText());
        result.put("coverLetter", application.getCoverLetter() == null ? "" : application.getCoverLetter());
        result.put("invitationText", application.getInvitationText() == null ? "" : application.getInvitationText());
        result.put("invitationExpiresAt", application.getInvitationExpiresAt());
        result.put("createdAt", application.getCreatedAt());
        result.put("updatedAt", application.getUpdatedAt());
        interviewService.findActiveByApplicationId(application.getId()).ifPresent(interview -> {
            result.put("interviewId", interview.getId());
            result.put("interviewStatus", interview.getStatus().name());
            result.put("scheduledAt", interview.getScheduledAt());
            result.put("durationMinutes", interview.getDurationMinutes());
        });
        return result;
    }

    public Map<String, Object> uiPayload(String title, Object payload) {
        return Map.of(
                "uiTitle", title,
                "uiPayload", toCamundaUiJson(payload),
                "loadedAt", Instant.now().toString()
        );
    }

    public Map<String, Object> recruiterScheduleUiPayload(WeekScheduleResponse schedule) {
        List<Map<String, Object>> items = schedule.getItems().stream()
                .map(item -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("start_at", item.getStartAt());
                    result.put("end_at", item.getEndAt());
                    result.put("application_id", item.getApplicationId());
                    result.put("candidate_email", item.getCandidateEmail());
                    result.put("interview_status", item.getInterviewStatus());
                    result.put("slot_status", item.getStatus());
                    return result;
                })
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("week_offset", schedule.getWeekOffset());
        result.put("week_start", schedule.getWeekStart());
        result.put("week_end", schedule.getWeekEnd());
        result.put("total_items", items.size());
        result.put("items", items);
        return result;
    }

    private String toCamundaUiJson(Object payload) {
        try {
            return truncateUiPayload(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            return truncateUiPayload(String.valueOf(payload));
        }
    }

    private String truncateUiPayload(String value) {
        if (value == null || value.length() <= UI_PAYLOAD_MAX_LENGTH) {
            return value;
        }
        return value.substring(0, UI_PAYLOAD_MAX_LENGTH) + "\n... truncated for Camunda Tasklist form ...";
    }
}
