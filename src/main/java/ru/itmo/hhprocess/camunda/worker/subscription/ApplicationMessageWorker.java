package ru.itmo.hhprocess.camunda.worker.subscription;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.utils.CamundaFormValidator;
import ru.itmo.hhprocess.utils.CamundaTaskVariables;
import ru.itmo.hhprocess.service.VacancyLifecycleService;

import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "app.camunda.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
@CamundaWorkerSubscriptions.ApplicationMessage
public class ApplicationMessageWorker extends AbstractExternalTaskWorker {

    private final VacancyLifecycleService vacancyLifecycleService;

    public ApplicationMessageWorker(CamundaFormValidator formValidator,
                                    VacancyLifecycleService vacancyLifecycleService) {
        super(formValidator);
        this.vacancyLifecycleService = vacancyLifecycleService;
    }

    @Override
    protected Map<String, Object> handle(String activityId, CamundaTaskVariables variables) {
        return vacancyLifecycleService.handleVacancyClosedMessage(
                variables.readRequiredUuid("applicationId"),
                variables.stringValue("closeReason"));
    }
}
