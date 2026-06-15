package ru.itmo.hhprocess.camunda.worker.subscription;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.utils.CamundaFormValidator;
import ru.itmo.hhprocess.utils.CamundaTaskVariables;

import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "app.camunda.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
@CamundaWorkerSubscriptions.StatusTransition
public class StatusTransitionWorker extends AbstractExternalTaskWorker {

    public StatusTransitionWorker(CamundaFormValidator formValidator) {
        super(formValidator);
    }

    @Override
    protected Map<String, Object> handle(String activityId, CamundaTaskVariables variables) {
        return Map.of(
                "currentStatus", variables.stringValue("status"),
                "statusAction", activityId,
                "requestedStatus", variables.stringValue("requestedStatus")
        );
    }
}
