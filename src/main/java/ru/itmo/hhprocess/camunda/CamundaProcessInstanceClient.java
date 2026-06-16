package ru.itmo.hhprocess.camunda;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class CamundaProcessInstanceClient {

    private final CamundaRestClient restClient;

    public Optional<String> startProcessByKey(String processKey, String businessKey, Map<String, ?> variables) {
        return restClient.startProcessByKey(processKey, businessKey, variables);
    }

    public boolean updateProcessInstanceBusinessKey(String processInstanceId, String businessKey) {
        return restClient.updateProcessInstanceBusinessKey(processInstanceId, businessKey);
    }

    public boolean hasActiveProcessInstance(String processKey, String businessKey) {
        return restClient.hasActiveProcessInstance(processKey, businessKey);
    }

    public boolean correlateMessage(String messageName, String businessKey, Map<String, ?> variables) {
        return restClient.correlateMessage(messageName, businessKey, variables);
    }
}
