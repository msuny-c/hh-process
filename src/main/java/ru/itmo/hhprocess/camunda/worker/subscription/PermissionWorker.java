package ru.itmo.hhprocess.camunda.worker.subscription;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.utils.CamundaFormValidator;
import ru.itmo.hhprocess.utils.CamundaTaskVariables;

import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "app.camunda.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
@CamundaWorkerSubscriptions.PermissionCheck
public class PermissionWorker extends AbstractExternalTaskWorker {

    public PermissionWorker(CamundaFormValidator formValidator) {
        super(formValidator);
    }

    @Override
    protected Map<String, Object> handle(String activityId, CamundaTaskVariables variables) {
        return Map.of(
                "permissionRole", "SYSTEM",
                "permissionOperation", activityId,
                "permissionOwnership", true,
                "permissionChecked", true
        );
    }
}
