package ru.itmo.hhprocess.camunda;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class CamundaTaskClient {

    private final CamundaRestClient restClient;

    public List<Map<String, Object>> findActiveTasks(String businessKey, String taskDefinitionKey) {
        return restClient.findActiveTasks(businessKey, taskDefinitionKey);
    }

    public List<Map<String, Object>> findActiveTasksByProcessInstanceId(String processInstanceId, String taskDefinitionKey) {
        return restClient.findActiveTasksByProcessInstanceId(processInstanceId, taskDefinitionKey);
    }

    public List<Map<String, Object>> findActiveUserTasks(int maxResults) {
        return restClient.findActiveUserTasks(maxResults);
    }

    public Map<String, Object> getTaskVariables(String taskId) {
        return restClient.getTaskVariables(taskId);
    }

    public boolean setTaskAssignee(String taskId, String userId) {
        return restClient.setTaskAssignee(taskId, userId);
    }

    public boolean ensureTaskUserAuthorization(String userId, String taskId) {
        return restClient.ensureTaskUserAuthorization(userId, taskId);
    }

    public boolean taskHasCandidateGroup(String taskId, String expectedGroup) {
        return restClient.taskHasCandidateGroup(taskId, expectedGroup);
    }

    public boolean completeTask(String taskId, Map<String, ?> variables) {
        return restClient.completeTask(taskId, variables);
    }
}
