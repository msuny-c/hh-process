package ru.itmo.hhprocess.camunda.handler;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.camunda.CamundaExternalTask;
import ru.itmo.hhprocess.camunda.CamundaExternalTaskHandler;
import ru.itmo.hhprocess.camunda.CamundaTaskTopics;
import ru.itmo.hhprocess.camunda.CamundaVariables;
import ru.itmo.hhprocess.dto.recruiter.InviteResponse;
import ru.itmo.hhprocess.service.InterviewProcessService;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.camunda.enabled", havingValue = "true")
public class ReserveInterviewSlotTaskHandler implements CamundaExternalTaskHandler {
    private final InterviewProcessService interviewProcessService;

    @Override
    public String topic() {
        return CamundaTaskTopics.RESERVE_INTERVIEW_SLOT;
    }

    @Override
    public Map<String, Object> handle(CamundaExternalTask task) {
        UUID applicationId = CamundaVariables.uuid(task.variables(), CamundaVariables.APPLICATION_ID);
        String recruiterUserId = CamundaVariables.string(task.variables(), CamundaVariables.RECRUITER_USER_ID);
        String message = CamundaVariables.string(task.variables(), CamundaVariables.RECRUITER_MESSAGE);
        Instant scheduledAt = CamundaVariables.instant(task.variables(), CamundaVariables.SCHEDULED_AT);
        Integer durationMinutes = CamundaVariables.integer(task.variables(), CamundaVariables.DURATION_MINUTES);
        InviteResponse response = interviewProcessService.inviteFromProcess(applicationId, recruiterUserId, message, scheduledAt, durationMinutes);
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put(CamundaVariables.INTERVIEW_ID, response.getInterviewId().toString());
        variables.put(CamundaVariables.INVITATION_EXPIRES_AT, response.getExpiresAt().toString());
        return variables;
    }
}
