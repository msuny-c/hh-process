package ru.itmo.hhprocess.camunda.handler;

import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.camunda.CamundaExternalTask;
import ru.itmo.hhprocess.camunda.CamundaExternalTaskHandler;
import ru.itmo.hhprocess.camunda.CamundaTaskTopics;
import ru.itmo.hhprocess.camunda.CamundaVariables;
import ru.itmo.hhprocess.dto.recruiter.InterviewActionResponse;
import ru.itmo.hhprocess.service.InterviewProcessService;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.camunda.enabled", havingValue = "true")
public class CancelInterviewTaskHandler implements CamundaExternalTaskHandler {
    private final InterviewProcessService interviewProcessService;

    @Override
    public String topic() {
        return CamundaTaskTopics.CANCEL_INTERVIEW;
    }

    @Override
    public Map<String, Object> handle(CamundaExternalTask task) {
        UUID interviewId = CamundaVariables.uuid(task.variables(), CamundaVariables.INTERVIEW_ID);
        String recruiterUserId = CamundaVariables.string(task.variables(), CamundaVariables.RECRUITER_USER_ID);
        String reason = CamundaVariables.string(task.variables(), CamundaVariables.INTERVIEW_CANCEL_REASON);
        InterviewActionResponse response = interviewProcessService.cancelInterviewFromProcess(interviewId, recruiterUserId, reason);
        return Map.of(CamundaVariables.APPLICATION_ID, response.getApplicationId().toString());
    }
}
