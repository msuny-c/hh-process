package ru.itmo.hhprocess.camunda.handler;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.camunda.CamundaExternalTask;
import ru.itmo.hhprocess.camunda.CamundaExternalTaskHandler;
import ru.itmo.hhprocess.camunda.CamundaTaskTopics;
import ru.itmo.hhprocess.camunda.CamundaVariables;
import ru.itmo.hhprocess.dto.recruiter.CreateVacancyRequest;
import ru.itmo.hhprocess.dto.recruiter.VacancyResponse;
import ru.itmo.hhprocess.service.VacancyService;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.camunda.enabled", havingValue = "true")
public class CreateVacancyTaskHandler implements CamundaExternalTaskHandler {
    private final VacancyService vacancyService;

    @Override
    public String topic() {
        return CamundaTaskTopics.CREATE_VACANCY;
    }

    @Override
    public Map<String, Object> handle(CamundaExternalTask task) {
        String recruiterUserId = CamundaVariables.string(task.variables(), CamundaVariables.RECRUITER_USER_ID);
        CreateVacancyRequest request = new CreateVacancyRequest();
        request.setTitle(CamundaVariables.string(task.variables(), CamundaVariables.VACANCY_TITLE));
        request.setDescription(CamundaVariables.string(task.variables(), CamundaVariables.VACANCY_DESCRIPTION));
        request.setRequiredSkills(parseSkills(CamundaVariables.string(task.variables(), CamundaVariables.REQUIRED_SKILLS_CSV)));
        request.setScreeningThreshold(CamundaVariables.integer(task.variables(), CamundaVariables.SCREENING_THRESHOLD));
        VacancyResponse response = vacancyService.createFromProcess(recruiterUserId, request);
        return Map.of(CamundaVariables.VACANCY_ID, response.getId().toString());
    }

    static List<String> parseSkills(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }
}
