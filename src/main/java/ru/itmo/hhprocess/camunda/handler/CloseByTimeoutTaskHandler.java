package ru.itmo.hhprocess.camunda.handler;

import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.camunda.CamundaExternalTask;
import ru.itmo.hhprocess.camunda.CamundaExternalTaskHandler;
import ru.itmo.hhprocess.camunda.CamundaTaskTopics;
import ru.itmo.hhprocess.camunda.CamundaVariables;
import ru.itmo.hhprocess.service.TimeoutBatchProcessor;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.camunda.enabled", havingValue = "true")
public class CloseByTimeoutTaskHandler implements CamundaExternalTaskHandler {
    private final TimeoutBatchProcessor timeoutBatchProcessor;

    @Override
    public String topic() {
        return CamundaTaskTopics.CLOSE_BY_TIMEOUT;
    }

    @Override
    public Map<String, Object> handle(CamundaExternalTask task) {
        UUID applicationId = CamundaVariables.uuid(task.variables(), CamundaVariables.APPLICATION_ID);
        timeoutBatchProcessor.processApplicationTimeout(applicationId);
        return Map.of();
    }
}
