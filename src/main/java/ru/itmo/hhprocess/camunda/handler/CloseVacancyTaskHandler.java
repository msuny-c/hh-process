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
import ru.itmo.hhprocess.dto.recruiter.CloseVacancyRequest;
import ru.itmo.hhprocess.service.VacancyLifecycleService;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.camunda.enabled", havingValue = "true")
public class CloseVacancyTaskHandler implements CamundaExternalTaskHandler {
    private final VacancyLifecycleService vacancyLifecycleService;

    @Override
    public String topic() {
        return CamundaTaskTopics.CLOSE_VACANCY;
    }

    @Override
    public Map<String, Object> handle(CamundaExternalTask task) {
        UUID vacancyId = CamundaVariables.uuid(task.variables(), CamundaVariables.VACANCY_ID);
        String recruiterUserId = CamundaVariables.string(task.variables(), CamundaVariables.RECRUITER_USER_ID);
        CloseVacancyRequest request = new CloseVacancyRequest();
        request.setReason(CamundaVariables.string(task.variables(), CamundaVariables.VACANCY_CLOSE_REASON));
        vacancyLifecycleService.closeVacancyFromProcess(vacancyId, recruiterUserId, request);
        return Map.of();
    }
}
