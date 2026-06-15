package ru.itmo.hhprocess.camunda.worker.subscription;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.utils.CamundaFormValidator;
import ru.itmo.hhprocess.utils.CamundaTaskVariables;
import ru.itmo.hhprocess.service.VacancyService;

import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "app.camunda.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
@CamundaWorkerSubscriptions.VacancyStatusUpdate
public class VacancyStatusUpdateWorker extends AbstractExternalTaskWorker {

    private final VacancyService vacancyService;

    public VacancyStatusUpdateWorker(CamundaFormValidator formValidator, VacancyService vacancyService) {
        super(formValidator);
        this.vacancyService = vacancyService;
    }

    @Override
    protected Map<String, Object> handle(String activityId, CamundaTaskVariables variables) {
        UUID vacancyId = variables.readRequiredUuid("vacancyId");
        UUID recruiterUserId = variables.readUuid("recruiterUserId");
        String requestedStatus = variables.stringValue("requestedStatus");
        return switch (activityId) {
            case "ValidateVacancyStatusUpdate" -> vacancyService.validateVacancyStatusUpdate(
                    vacancyId, recruiterUserId, variables.stringValue("starterUserId"), requestedStatus);
            case "ApplyVacancyStatusUpdate" -> vacancyService.applyVacancyStatusUpdate(
                    vacancyId, recruiterUserId, variables.stringValue("starterUserId"), requestedStatus);
            default -> Map.of("vacancyStatusUpdateIgnored", true, "activityId", activityId);
        };
    }
}
