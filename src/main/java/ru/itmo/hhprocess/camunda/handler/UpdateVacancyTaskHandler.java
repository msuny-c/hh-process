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
import ru.itmo.hhprocess.dto.recruiter.UpdateVacancyRequest;
import ru.itmo.hhprocess.enums.VacancyStatus;
import ru.itmo.hhprocess.service.VacancyService;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.camunda.enabled", havingValue = "true")
public class UpdateVacancyTaskHandler implements CamundaExternalTaskHandler {
    private final VacancyService vacancyService;

    @Override
    public String topic() {
        return CamundaTaskTopics.UPDATE_VACANCY;
    }

    @Override
    public Map<String, Object> handle(CamundaExternalTask task) {
        UUID vacancyId = CamundaVariables.uuid(task.variables(), CamundaVariables.VACANCY_ID);
        String recruiterUserId = CamundaVariables.string(task.variables(), CamundaVariables.RECRUITER_USER_ID);
        UpdateVacancyRequest request = new UpdateVacancyRequest();
        request.setTitle(CamundaVariables.string(task.variables(), CamundaVariables.VACANCY_TITLE));
        request.setDescription(CamundaVariables.string(task.variables(), CamundaVariables.VACANCY_DESCRIPTION));
        String skills = CamundaVariables.string(task.variables(), CamundaVariables.REQUIRED_SKILLS_CSV);
        if (skills != null && !skills.isBlank()) {
            request.setRequiredSkills(CreateVacancyTaskHandler.parseSkills(skills));
        }
        request.setScreeningThreshold(CamundaVariables.integer(task.variables(), CamundaVariables.SCREENING_THRESHOLD));
        String status = CamundaVariables.string(task.variables(), CamundaVariables.VACANCY_STATUS);
        if (status != null && !status.isBlank()) {
            request.setStatus(VacancyStatus.valueOf(status));
        }
        vacancyService.updateFromProcess(vacancyId, recruiterUserId, request);
        return Map.of();
    }
}
