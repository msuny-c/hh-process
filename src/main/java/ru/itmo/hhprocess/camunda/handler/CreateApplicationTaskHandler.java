package ru.itmo.hhprocess.camunda.handler;

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
import ru.itmo.hhprocess.service.ApplicationService;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.camunda.enabled", havingValue = "true")
public class CreateApplicationTaskHandler implements CamundaExternalTaskHandler {
    private final ApplicationService applicationService;

    @Override
    public String topic() {
        return CamundaTaskTopics.CREATE_APPLICATION;
    }

    @Override
    public Map<String, Object> handle(CamundaExternalTask task) {
        UUID vacancyId = CamundaVariables.uuid(task.variables(), CamundaVariables.VACANCY_ID);
        String candidateUserId = CamundaVariables.string(task.variables(), CamundaVariables.CANDIDATE_USER_ID);
        if (candidateUserId == null || candidateUserId.isBlank()) {
            candidateUserId = CamundaVariables.string(task.variables(), CamundaVariables.CANDIDATE_EMAIL);
        }
        String resumeText = CamundaVariables.string(task.variables(), CamundaVariables.RESUME_TEXT);
        String coverLetter = CamundaVariables.string(task.variables(), CamundaVariables.COVER_LETTER);
        ApplicationService.ApplicationProcessResult result = applicationService.createFromProcess(vacancyId, candidateUserId, resumeText, coverLetter);
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put(CamundaVariables.APPLICATION_ID, result.applicationId().toString());
        variables.put(CamundaVariables.CANDIDATE_USER_ID, result.candidateUserId());
        variables.put(CamundaVariables.RECRUITER_USER_ID, result.recruiterUserId());
        return variables;
    }
}
