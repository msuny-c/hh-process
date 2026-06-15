package ru.itmo.hhprocess.camunda.worker.subscription;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.utils.CamundaFormValidator;
import ru.itmo.hhprocess.utils.CamundaTaskVariables;
import ru.itmo.hhprocess.service.NotificationService;

import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "app.camunda.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
@CamundaWorkerSubscriptions.NotificationDispatch
public class NotificationDispatchWorker extends AbstractExternalTaskWorker {
    private final NotificationService notificationService;

    public NotificationDispatchWorker(CamundaFormValidator formValidator,
                                      NotificationService notificationService) {
        super(formValidator);
        this.notificationService = notificationService;
    }

    @Override
    protected Map<String, Object> handle(String activityId, CamundaTaskVariables variables) {
        UUID applicationId = variables.readUuid("applicationId");
        if (applicationId == null) {
            applicationId = variables.readUuid("expiredApplicationId");
        }
        return notificationService.dispatchNotification(variables.stringValue("notificationKind"), applicationId, variables.readUuid("vacancyId"), variables.stringValue("invitationMessage"), variables.stringValue("closeReason"), variables.stringValue("cancelReason"), variables.stringValue("resetReason"), variables.stringValue("notificationTemplateCode"));
    }
}
