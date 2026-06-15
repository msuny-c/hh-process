package ru.itmo.hhprocess.camunda.worker.subscription;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.utils.CamundaFormValidator;
import ru.itmo.hhprocess.utils.CamundaTaskVariables;
import ru.itmo.hhprocess.service.VacancyService;

import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "app.camunda.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
@CamundaWorkerSubscriptions.VacancyCreate
public class VacancyCreateWorker extends AbstractExternalTaskWorker {

    private final VacancyService vacancyService;

    public VacancyCreateWorker(CamundaFormValidator formValidator, VacancyService vacancyService) {
        super(formValidator);
        this.vacancyService = vacancyService;
    }

    @Override
    protected Map<String, Object> handle(String activityId, CamundaTaskVariables variables) {
        return switch (activityId) {
            case "ValidateCreateVacancyForm" -> vacancyService.validateCreateVacancyForm(
                    variables.stringValue("starterUserId"),
                    variables.readUuid("recruiterUserId"),
                    variables.stringValue("title"),
                    variables.stringValue("description"),
                    variables.readValue("requiredSkills"),
                    variables.readValue("screeningThreshold")
            );
            case "CreateVacancyFromForm" -> vacancyService.createVacancyFromCamundaForm(
                    variables.stringValue("starterUserId"),
                    variables.readUuid("recruiterUserId"),
                    variables.stringValue("title"),
                    variables.stringValue("description"),
                    variables.readValue("requiredSkills"),
                    variables.readValue("screeningThreshold"),
                    variables.processInstanceId()
            );
            default -> Map.of("vacancyCreateIgnored", true, "activityId", activityId);
        };
    }
}
