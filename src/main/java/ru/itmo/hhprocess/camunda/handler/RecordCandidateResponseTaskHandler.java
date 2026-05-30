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
import ru.itmo.hhprocess.enums.ResponseType;
import ru.itmo.hhprocess.service.InvitationResponseService;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.camunda.enabled", havingValue = "true")
public class RecordCandidateResponseTaskHandler implements CamundaExternalTaskHandler {
    private final InvitationResponseService invitationResponseService;

    @Override
    public String topic() {
        return CamundaTaskTopics.RECORD_CANDIDATE_RESPONSE;
    }

    @Override
    public Map<String, Object> handle(CamundaExternalTask task) {
        UUID applicationId = CamundaVariables.uuid(task.variables(), CamundaVariables.APPLICATION_ID);
        String candidateUserId = CamundaVariables.string(task.variables(), CamundaVariables.CANDIDATE_USER_ID);
        String response = CamundaVariables.string(task.variables(), CamundaVariables.CANDIDATE_RESPONSE);
        String message = CamundaVariables.string(task.variables(), CamundaVariables.CANDIDATE_MESSAGE);
        invitationResponseService.respondFromProcess(applicationId, candidateUserId, ResponseType.valueOf(response), message);
        return Map.of();
    }
}
