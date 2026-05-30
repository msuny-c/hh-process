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
import ru.itmo.hhprocess.service.InterviewProcessService;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.camunda.enabled", havingValue = "true")
public class RejectApplicationTaskHandler implements CamundaExternalTaskHandler {
    private final InterviewProcessService interviewProcessService;

    @Override
    public String topic() {
        return CamundaTaskTopics.REJECT_APPLICATION;
    }

    @Override
    public Map<String, Object> handle(CamundaExternalTask task) {
        UUID applicationId = CamundaVariables.uuid(task.variables(), CamundaVariables.APPLICATION_ID);
        String recruiterUserId = CamundaVariables.string(task.variables(), CamundaVariables.RECRUITER_USER_ID);
        String message = CamundaVariables.string(task.variables(), CamundaVariables.RECRUITER_MESSAGE);
        interviewProcessService.rejectFromProcess(applicationId, recruiterUserId, message);
        return Map.of();
    }
}
