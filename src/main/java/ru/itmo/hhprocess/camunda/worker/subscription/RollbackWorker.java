package ru.itmo.hhprocess.camunda.worker.subscription;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.utils.CamundaFormValidator;
import ru.itmo.hhprocess.camunda.CamundaProcessVariables;
import ru.itmo.hhprocess.utils.CamundaTaskVariables;
import ru.itmo.hhprocess.service.ApplicationService;
import ru.itmo.hhprocess.service.VacancyLifecycleService;

import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "app.camunda.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
@CamundaWorkerSubscriptions.Rollback
public class RollbackWorker extends AbstractExternalTaskWorker {
    private final ApplicationService applicationService;
    private final VacancyLifecycleService vacancyLifecycleService;

    public RollbackWorker(CamundaFormValidator formValidator,
                          ApplicationService applicationService, VacancyLifecycleService vacancyLifecycleService) {
        super(formValidator);
        this.applicationService = applicationService;
        this.vacancyLifecycleService = vacancyLifecycleService;
    }

    @Override
    protected Map<String, Object> handle(String activityId, CamundaTaskVariables variables) {
        return switch (activityId) {
            case "RollbackApplicationTransaction", "RollbackAdminReset", "RollbackRecruiterCancel" -> {
                var result = applicationService.rollbackApplicationTransaction(
                        variables.readRequiredUuid("applicationId"),
                        variables.stringValue("rollbackReason"));
                yield CamundaProcessVariables.rollbackCompleted(result.application(), result.oldStatus());
            }
            case "RollbackVacancyTransaction" -> vacancyLifecycleService.rollbackVacancyTransaction(
                    variables.readRequiredUuid("vacancyId"),
                    variables.stringValue("rollbackReason"));
            default -> Map.of("rollbackIgnored", true, "activityId", activityId);
        };
    }
}
