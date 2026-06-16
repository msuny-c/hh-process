package ru.itmo.hhprocess.camunda;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class CamundaExternalTaskClient {

    private final CamundaRestClient restClient;

    public boolean isEnabled() {
        return restClient.isEnabled();
    }

    public List<Map<String, Object>> fetchAndLockExternalTasks(List<String> topics) {
        return restClient.fetchAndLockExternalTasks(topics);
    }

    public void completeExternalTask(String externalTaskId, Map<String, ?> variables) {
        restClient.completeExternalTask(externalTaskId, variables);
    }

    public boolean throwBpmnErrorExternalTask(String externalTaskId, String errorCode, String message, Map<String, ?> variables) {
        return restClient.throwBpmnErrorExternalTask(externalTaskId, errorCode, message, variables);
    }

    public void failExternalTask(String externalTaskId, String message, String details) {
        restClient.failExternalTask(externalTaskId, message, details);
    }
}
