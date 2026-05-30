package ru.itmo.hhprocess.camunda;

import java.util.Map;

public interface CamundaExternalTaskHandler {
    String topic();
    Map<String, Object> handle(CamundaExternalTask task) throws Exception;
}
