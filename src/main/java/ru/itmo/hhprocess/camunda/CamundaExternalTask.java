package ru.itmo.hhprocess.camunda;

import java.util.Map;

public record CamundaExternalTask(
        String id,
        String topicName,
        String processInstanceId,
        String businessKey,
        Map<String, CamundaVariableValue> variables
) {
}
