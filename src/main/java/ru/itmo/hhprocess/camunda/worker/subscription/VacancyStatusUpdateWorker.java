package ru.itmo.hhprocess.camunda.worker.subscription;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.utils.CamundaFormValidator;
import ru.itmo.hhprocess.utils.CamundaTaskVariables;
import ru.itmo.hhprocess.service.NotificationService;
import ru.itmo.hhprocess.service.VacancyLifecycleService;
import ru.itmo.hhprocess.service.VacancyService;

import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "app.camunda.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
@CamundaWorkerSubscriptions.VacancyStatusUpdate
public class VacancyStatusUpdateWorker extends VacancyFlowWorker {
    public VacancyStatusUpdateWorker(CamundaFormValidator formValidator,
                                     VacancyService vacancyService,
                                     VacancyLifecycleService vacancyLifecycleService,
                                     NotificationService notificationService) {
        super(formValidator, vacancyService, vacancyLifecycleService, notificationService);
    }

    @Override
    protected Map<String, Object> handle(String activityId, CamundaTaskVariables variables) {
        return handleStatusUpdate(activityId, variables);
    }
}
