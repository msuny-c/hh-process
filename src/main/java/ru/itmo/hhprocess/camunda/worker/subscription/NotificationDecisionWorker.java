package ru.itmo.hhprocess.camunda.worker.subscription;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.utils.CamundaFormValidator;
import ru.itmo.hhprocess.utils.CamundaTaskVariables;
import ru.itmo.hhprocess.service.NotificationService;

import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "app.camunda.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
@CamundaWorkerSubscriptions.NotificationDecision
public class NotificationDecisionWorker extends AbstractExternalTaskWorker {
    private final NotificationService notificationService;

    public NotificationDecisionWorker(CamundaFormValidator formValidator,
                                      NotificationService notificationService) {
        super(formValidator);
        this.notificationService = notificationService;
    }

    @Override
    protected Map<String, Object> handle(String activityId, CamundaTaskVariables variables) {
        return notificationService.prepareNotificationDecision(variables.stringValue("notificationKind"), variables.readUuid("applicationId"), variables.readUuid("vacancyId"), variables.stringValue("recipientRole"));
    }
}
