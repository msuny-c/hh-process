package ru.itmo.hhprocess.camunda;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import ru.itmo.hhprocess.config.CamundaProperties;
import ru.itmo.hhprocess.utils.CamundaVariable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class CamundaRestClient {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> TASK_LIST =
            new ParameterizedTypeReference<>() {};

    private final RestTemplate camundaRestTemplate;
    private final CamundaProperties properties;

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public Optional<String> deploy(String deploymentName, Map<String, Resource> resources) {
        if (!enabled()) {
            return Optional.empty();
        }
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("deployment-name", deploymentName);
        int index = 0;
        for (Map.Entry<String, Resource> entry : resources.entrySet()) {
            String partName = "resource-" + index++;
            body.add(partName, namedResource(partName, entry.getKey(), entry.getValue()));
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return post("/deployment/create", new HttpEntity<>(body, headers), Map.class, "deploy BPMN/resources")
                .map(response -> response.get("id"))
                .map(String::valueOf);
    }

    public boolean ensureGroupExists(String groupId, String groupName) {
        return ensureGroupExists(groupId, groupName, "WORKFLOW");
    }

    public boolean ensureGroupExists(String groupId, String groupName, String groupType) {
        if (!enabled()) {
            return false;
        }
        if (!getList("/group", Map.of("id", groupId), "read Camunda group " + groupId).isEmpty()) {
            return true;
        }
        Map<String, Object> body = Map.of(
                "id", groupId,
                "name", groupName,
                "type", groupType == null || groupType.isBlank() ? "WORKFLOW" : groupType);
        return postVoid("/group/create", body, "create Camunda group " + groupId);
    }

    public boolean ensureProcessStartAuthorization(String groupId, String processDefinitionKey) {
        return ensureAuthorization(Map.of(
                "type", 1,
                "groupId", groupId,
                "resourceType", 6,
                "resourceId", processDefinitionKey,
                "permissions", List.of("CREATE_INSTANCE", "READ")), 6, processDefinitionKey);
    }

    public boolean ensureFilterReadAuthorization(String groupId, String filterId) {
        return ensureGroupAuthorization(groupId, 5, filterId, List.of("READ"));
    }

    public boolean ensureGroupAuthorization(String groupId, int resourceType, String resourceId, List<String> permissions) {
        String targetResourceId = resourceId == null || resourceId.isBlank() ? "*" : resourceId;
        List<String> targetPermissions = permissions == null || permissions.isEmpty() ? List.of("READ") : permissions;
        Map<String, Object> body = Map.of(
                "type", 1,
                "groupId", groupId,
                "resourceType", resourceType,
                "resourceId", targetResourceId,
                "permissions", targetPermissions);
        return ensureAuthorization(body, resourceType, targetResourceId);
    }

    public boolean deleteGroupAuthorization(String groupId, int resourceType, String resourceId) {
        if (!enabled()) {
            return false;
        }
        String targetResourceId = resourceId == null || resourceId.isBlank() ? "*" : resourceId;
        boolean deleted = false;
        for (Map<String, Object> item : getList("/authorization", Map.of(
                "type", 1,
                "groupIdIn", groupId,
                "resourceType", resourceType,
                "resourceId", targetResourceId), "delete Camunda authorization")) {
            Object id = item.get("id");
            if (id != null) {
                deleteVoid("/authorization/" + encodePath(String.valueOf(id)), "delete Camunda authorization " + id);
                deleted = true;
            }
        }
        return deleted;
    }

    public List<String> findFilterIdsByName(String name) {
        return getList("/filter", Map.of("name", name), "find Camunda filters name=" + name).stream()
                .map(item -> item.get("id"))
                .filter(id -> id != null)
                .map(String::valueOf)
                .toList();
    }

    public void deleteFilter(String filterId) {
        if (enabled()) {
            deleteVoid("/filter/" + encodePath(filterId), "delete Camunda filter " + filterId);
        }
    }

    public Optional<String> createTaskFilter(String name, Map<String, ?> query, Map<String, ?> filterProperties) {
        Map<String, Object> body = Map.of(
                "name", name,
                "resourceType", "Task",
                "query", query == null ? Map.of() : query,
                "properties", filterProperties == null ? Map.of() : filterProperties);
        return post("/filter/create", body, Map.class, "create Camunda task filter " + name)
                .map(response -> response.get("id"))
                .map(String::valueOf);
    }

    public boolean updateTaskFilter(String filterId, String name, Map<String, ?> query, Map<String, ?> filterProperties) {
        Map<String, Object> body = Map.of(
                "name", name,
                "resourceType", "Task",
                "query", query == null ? Map.of() : query,
                "properties", filterProperties == null ? Map.of() : filterProperties);
        return putVoid("/filter/" + encodePath(filterId), body, "update Camunda task filter " + name);
    }

    public boolean ensureUserExists(String userId, String email, String firstName, String lastName, String initialPassword) {
        return ensureUserExists(userId, email, firstName, lastName, initialPassword, false);
    }

    public boolean ensureUserExists(String userId, String email, String firstName, String lastName,
                                    String initialPassword, boolean updatePasswordWhenExists) {
        if (!enabled()) {
            return false;
        }
        String encodedUserId = encodePath(userId);
        boolean exists = get("/user/" + encodedUserId + "/profile", Map.class, "read Camunda user " + userId).isPresent();
        Map<String, Object> profile = Map.of(
                "id", userId,
                "firstName", firstName == null ? "" : firstName,
                "lastName", lastName == null ? "" : lastName,
                "email", email == null ? userId : email);
        if (!exists) {
            Map<String, Object> body = Map.of(
                    "profile", profile,
                    "credentials", Map.of("password", blankToDefault(initialPassword, "camunda")));
            return postVoid("/user/create", body, "create Camunda user " + userId);
        }
        if (!putVoid("/user/" + encodedUserId + "/profile", profile, "update Camunda user profile " + userId)) {
            return false;
        }
        if (updatePasswordWhenExists && initialPassword != null && !initialPassword.isBlank()) {
            putVoid("/user/" + encodedUserId + "/credentials", Map.of("password", initialPassword),
                    "update Camunda user credentials " + userId);
        }
        return true;
    }

    public boolean ensureMembershipExists(String userId, String groupId) {
        if (!enabled()) {
            return false;
        }
        if (!getList("/group", Map.of("member", userId, "id", groupId),
                "check Camunda group membership user=" + userId + " group=" + groupId).isEmpty()) {
            return true;
        }
        return putVoid("/group/" + encodePath(groupId) + "/members/" + encodePath(userId), null,
                "create Camunda group membership user=" + userId + " group=" + groupId);
    }

    public Optional<String> startProcessByKey(String processKey, String businessKey, Map<String, ?> variables) {
        Map<String, Object> body = Map.of(
                "businessKey", businessKey,
                "variables", CamundaVariable.variables(variables));
        return post("/process-definition/key/" + processKey + "/start", body, Map.class,
                "start Camunda process " + processKey + " businessKey=" + businessKey)
                .map(response -> response.get("id"))
                .map(String::valueOf);
    }

    public boolean updateProcessInstanceBusinessKey(String processInstanceId, String businessKey) {
        if (!enabled() || processInstanceId == null || processInstanceId.isBlank()
                || businessKey == null || businessKey.isBlank()) {
            return false;
        }
        try {
            camundaRestTemplate.put(url("/process-instance/" + encodePath(processInstanceId) + "/business-key"),
                    Map.of("businessKey", businessKey));
            return true;
        } catch (RuntimeException e) {
            logSoftFailure("update Camunda process businessKey processInstanceId=" + processInstanceId, e);
            return false;
        }
    }

    public boolean hasActiveProcessInstance(String processKey, String businessKey) {
        return !getList("/process-instance", Map.of(
                "processDefinitionKey", processKey,
                "businessKey", businessKey,
                "active", true), "find active Camunda process " + processKey + " businessKey=" + businessKey).isEmpty();
    }

    public boolean correlateMessage(String messageName, String businessKey, Map<String, ?> variables) {
        Map<String, Object> body = Map.of(
                "messageName", messageName,
                "businessKey", businessKey,
                "processVariables", CamundaVariable.variables(variables));
        try {
            camundaRestTemplate.postForEntity(url("/message"), body, Map.class);
            return true;
        } catch (HttpStatusCodeException e) {
            if (isMessageCorrelationMiss(e)) {
                logSoftFailure("correlate Camunda message " + messageName + " businessKey=" + businessKey, e);
                return false;
            }
            handle("correlate Camunda message " + messageName + " businessKey=" + businessKey, e);
            return false;
        } catch (RuntimeException e) {
            handle("correlate Camunda message " + messageName + " businessKey=" + businessKey, e);
            return false;
        }
    }

    public List<Map<String, Object>> findActiveTasks(String businessKey, String taskDefinitionKey) {
        return getTaskList("/task", Map.of(
                "processInstanceBusinessKey", businessKey,
                "taskDefinitionKey", taskDefinitionKey,
                "active", true), "find Camunda task " + taskDefinitionKey + " businessKey=" + businessKey);
    }

    public List<Map<String, Object>> findActiveUserTasks(int maxResults) {
        return getTaskList("/task", Map.of(
                "active", true,
                "sortBy", "created",
                "sortOrder", "desc",
                "maxResults", Math.max(1, maxResults)), "find active Camunda user tasks");
    }

    public Map<String, Object> getTaskVariables(String taskId) {
        return get("/task/" + encodePath(taskId) + "/variables", Map.class, "read Camunda task variables " + taskId)
                .map(raw -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    raw.forEach((key, value) -> result.put(String.valueOf(key), value));
                    return result;
                })
                .orElse(Map.of());
    }

    public boolean setTaskAssignee(String taskId, String userId) {
        return postVoid("/task/" + encodePath(taskId) + "/assignee", Map.of("userId", userId),
                "assign Camunda task " + taskId + " to " + userId);
    }

    public boolean ensureTaskUserAuthorization(String userId, String taskId) {
        Map<String, Object> body = Map.of(
                "type", 1,
                "userId", userId,
                "resourceType", 7,
                "resourceId", taskId,
                "permissions", List.of("READ", "UPDATE", "TASK_WORK"));
        return ensureAuthorization(body, 7, taskId);
    }

    public boolean completeTask(String taskId, Map<String, ?> variables) {
        Map<String, Object> body = Map.of("variables", CamundaVariable.variables(variables));
        try {
            camundaRestTemplate.postForEntity(url("/task/" + taskId + "/complete"), body, Void.class);
            return true;
        } catch (HttpStatusCodeException e) {
            if (isConcurrentTaskUpdate(e)) {
                logSoftFailure("complete Camunda task " + taskId, e);
                return false;
            }
            handle("complete Camunda task " + taskId, e);
            return false;
        } catch (RuntimeException e) {
            handle("complete Camunda task " + taskId, e);
            return false;
        }
    }

    public boolean completeFirstTask(String businessKey, String taskDefinitionKey, Map<String, ?> variables) {
        List<Map<String, Object>> tasks = findActiveTasks(businessKey, taskDefinitionKey);
        if (tasks.isEmpty() || tasks.get(0).get("id") == null) {
            return false;
        }
        return completeTask(String.valueOf(tasks.get(0).get("id")), variables);
    }

    private boolean ensureAuthorization(Map<String, Object> body, int resourceType, String resourceId) {
        if (!enabled()) {
            return false;
        }
        String queryKey = body.containsKey("userId") ? "userIdIn" : "groupIdIn";
        String queryValue = String.valueOf(body.getOrDefault("userId", body.get("groupId")));
        List<Map<String, Object>> existing = getList("/authorization", Map.of(
                "type", 1,
                queryKey, queryValue,
                "resourceType", resourceType,
                "resourceId", resourceId), "check Camunda authorization");
        if (!existing.isEmpty()) {
            Object id = existing.get(0).get("id");
            if (id != null) {
                return putVoid("/authorization/" + encodePath(String.valueOf(id)), body, "update Camunda authorization");
            }
        }
        return postVoid("/authorization/create", body, "create Camunda authorization");
    }

    private List<Map<String, Object>> getTaskList(String path, Map<String, ?> queryParams, String operation) {
        return getList(path, queryParams, operation);
    }

    private List<Map<String, Object>> getList(String path, Map<String, ?> queryParams, String operation) {
        if (!enabled()) {
            return List.of();
        }
        try {
            ResponseEntity<List<Map<String, Object>>> response = camundaRestTemplate.exchange(
                    buildUri(path, queryParams), HttpMethod.GET, null, TASK_LIST);
            return response.getBody() == null ? List.of() : response.getBody();
        } catch (RuntimeException e) {
            handle(operation, e);
            return List.of();
        }
    }

    private <T> Optional<T> get(String path, Class<T> type, String operation) {
        if (!enabled()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(camundaRestTemplate.getForObject(url(path), type));
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().is4xxClientError()) {
                return Optional.empty();
            }
            handle(operation, e);
            return Optional.empty();
        } catch (RuntimeException e) {
            handle(operation, e);
            return Optional.empty();
        }
    }

    private <T> Optional<T> post(String path, Object body, Class<T> type, String operation) {
        if (!enabled()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(camundaRestTemplate.postForObject(url(path), body, type));
        } catch (RuntimeException e) {
            handle(operation, e);
            return Optional.empty();
        }
    }

    private boolean postVoid(String path, Object body, String operation) {
        if (!enabled()) {
            return false;
        }
        try {
            camundaRestTemplate.postForEntity(url(path), body, Void.class);
            return true;
        } catch (RuntimeException e) {
            handle(operation, e);
            return false;
        }
    }

    private boolean putVoid(String path, Object body, String operation) {
        if (!enabled()) {
            return false;
        }
        try {
            camundaRestTemplate.put(url(path), body);
            return true;
        } catch (RuntimeException e) {
            handle(operation, e);
            return false;
        }
    }

    private void deleteVoid(String path, String operation) {
        if (!enabled()) {
            return;
        }
        try {
            camundaRestTemplate.delete(url(path));
        } catch (RuntimeException e) {
            handle(operation, e);
        }
    }

    private String buildUri(String path, Map<String, ?> queryParams) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(url(path));
        queryParams.forEach(builder::queryParam);
        return builder.toUriString();
    }

    private boolean enabled() {
        return properties.isEnabled();
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String encodePath(String value) {
        return UriUtils.encodePathSegment(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private String url(String path) {
        String baseUrl = properties.getBaseUrl();
        if (baseUrl.endsWith("/") && path.startsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1) + path;
        }
        if (!baseUrl.endsWith("/") && !path.startsWith("/")) {
            return baseUrl + "/" + path;
        }
        return baseUrl + path;
    }

    private HttpEntity<Resource> namedResource(String partName, String filename, Resource resource) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDispositionFormData(partName, filename);
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        return new HttpEntity<>(resource, headers);
    }

    private void handle(String action, RuntimeException e) {
        logSoftFailure(action, e);
        if (properties.isFailOnError()) {
            throw e;
        }
    }

    private void logSoftFailure(String action, RuntimeException e) {
        if (e instanceof HttpStatusCodeException statusCodeException) {
            log.warn("Cannot {}: status={}, body={}", action, statusCodeException.getStatusCode(),
                    statusCodeException.getResponseBodyAsString());
        } else {
            log.warn("Cannot {}: {}", action, e.getMessage());
        }
    }

    private boolean isMessageCorrelationMiss(HttpStatusCodeException e) {
        String body = e.getResponseBodyAsString();
        return e.getStatusCode().is4xxClientError()
                && (body.contains("MismatchingMessageCorrelationException") || body.contains("Cannot correlate message"));
    }

    private boolean isConcurrentTaskUpdate(HttpStatusCodeException e) {
        String body = e.getResponseBodyAsString();
        return e.getStatusCode().is5xxServerError()
                && (body.contains("updated by another transaction concurrently") || body.contains("ENGINE-03005"));
    }
}
