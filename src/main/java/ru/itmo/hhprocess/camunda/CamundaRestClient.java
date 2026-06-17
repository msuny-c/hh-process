package ru.itmo.hhprocess.camunda;

import ru.itmo.hhprocess.config.CamundaProperties;
import ru.itmo.hhprocess.utils.CamundaVariable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class CamundaRestClient {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestTemplate camundaRestTemplate;
    private final CamundaProperties properties;

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public boolean isAvailable() {
        if (!properties.isEnabled()) {
            return false;
        }
        try {
            camundaRestTemplate.getForEntity(url("/version"), Void.class);
            return true;
        } catch (RuntimeException e) {
            log.warn("Camunda REST API is not available at {}: {}", properties.getBaseUrl(), e.getMessage());
            return false;
        }
    }

    public Optional<String> deploy(String deploymentName, Map<String, Resource> resources) {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("deployment-name", deploymentName);
            int index = 0;
            for (Map.Entry<String, Resource> entry : resources.entrySet()) {
                String partName = "resource-" + index++;
                body.add(partName, namedResource(partName, entry.getKey(), entry.getValue()));
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            Map<String, Object> response = camundaRestTemplate.exchange(
                    url("/deployment/create"), HttpMethod.POST, new HttpEntity<>(body, headers), MAP_TYPE).getBody();
            return Optional.ofNullable(response == null ? null : response.get("id")).map(String::valueOf);
        } catch (RuntimeException e) {
            handle("deploy BPMN/resources", e);
            return Optional.empty();
        }
    }

    public List<String> findDeploymentIdsByProcessDefinitionKey(String processDefinitionKey) {
        if (!properties.isEnabled()) {
            return List.of();
        }
        try {
            String uri = UriComponentsBuilder.fromUriString(url("/process-definition"))
                    .queryParam("key", processDefinitionKey)
                    .toUriString();
            List<Map<String, Object>> raw = camundaRestTemplate.exchange(uri, HttpMethod.GET, null, LIST_TYPE).getBody();
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            Set<String> ids = new LinkedHashSet<>();
            for (Map<String, Object> item : raw) {
                if (item.get("deploymentId") != null) {
                    ids.add(String.valueOf(item.get("deploymentId")));
                }
            }
            return new ArrayList<>(ids);
        } catch (RuntimeException e) {
            handle("find Camunda deployments by process key " + processDefinitionKey, e);
            return List.of();
        }
    }

    public void deleteDeploymentCascade(String deploymentId) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            String uri = UriComponentsBuilder.fromUriString(url("/deployment/" + encodePath(deploymentId)))
                    .queryParam("cascade", true)
                    .queryParam("skipCustomListeners", true)
                    .queryParam("skipIoMappings", true)
                    .toUriString();
            camundaRestTemplate.delete(uri);
        } catch (RuntimeException e) {
            handle("delete Camunda deployment " + deploymentId, e);
        }
    }

    public boolean ensureGroupExists(String groupId, String groupName) {
        return ensureGroupExists(groupId, groupName, "WORKFLOW");
    }

    public boolean ensureGroupExists(String groupId, String groupName, String groupType) {
        if (!properties.isEnabled()) {
            return false;
        }
        try {
            String uri = UriComponentsBuilder.fromUriString(url("/group"))
                    .queryParam("id", groupId)
                    .toUriString();
            List<Map<String, Object>> raw = camundaRestTemplate.exchange(uri, HttpMethod.GET, null, LIST_TYPE).getBody();
            if (raw != null && !raw.isEmpty()) {
                return true;
            }
        } catch (RuntimeException e) {
            handle("read Camunda group " + groupId, e);
            return false;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", groupId);
        body.put("name", groupName);
        body.put("type", groupType == null || groupType.isBlank() ? "WORKFLOW" : groupType);
        try {
            camundaRestTemplate.postForEntity(url("/group/create"), body, Void.class);
            return true;
        } catch (RuntimeException e) {
            handle("create Camunda group " + groupId, e);
            return false;
        }
    }

    public boolean ensureProcessStartAuthorization(String groupId, String processDefinitionKey) {
        if (!properties.isEnabled()) {
            return false;
        }
        try {
            String uri = UriComponentsBuilder.fromUriString(url("/authorization"))
                    .queryParam("type", 1)
                    .queryParam("groupIdIn", groupId)
                    .queryParam("resourceType", 6)
                    .queryParam("resourceId", processDefinitionKey)
                    .toUriString();
            List<Map<String, Object>> raw = camundaRestTemplate.exchange(uri, HttpMethod.GET, null, LIST_TYPE).getBody();
            if (raw != null && !raw.isEmpty()) {
                return true;
            }
        } catch (RuntimeException e) {
            handle("check Camunda start authorization group=" + groupId + " process=" + processDefinitionKey, e);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", 1);
        body.put("groupId", groupId);
        body.put("resourceType", 6);
        body.put("resourceId", processDefinitionKey);
        body.put("permissions", List.of("CREATE_INSTANCE", "READ"));
        try {
            camundaRestTemplate.postForEntity(url("/authorization/create"), body, Void.class);
            return true;
        } catch (RuntimeException e) {
            handle("create Camunda start authorization group=" + groupId + " process=" + processDefinitionKey, e);
            return false;
        }
    }

    public boolean ensureFilterReadAuthorization(String groupId, String filterId) {
        return ensureGroupAuthorization(groupId, 5, filterId, List.of("READ"));
    }

    public boolean deleteGroupAuthorization(String groupId, int resourceType, String resourceId) {
        if (!properties.isEnabled()) {
            return false;
        }
        String targetResourceId = resourceId == null || resourceId.isBlank() ? "*" : resourceId;
        boolean deleted = false;
        try {
            String uri = UriComponentsBuilder.fromUriString(url("/authorization"))
                    .queryParam("type", 1)
                    .queryParam("groupIdIn", groupId)
                    .queryParam("resourceType", resourceType)
                    .queryParam("resourceId", targetResourceId)
                    .toUriString();
            List<Map<String, Object>> raw = camundaRestTemplate.exchange(uri, HttpMethod.GET, null, LIST_TYPE).getBody();
            if (raw == null || raw.isEmpty()) {
                return false;
            }
            for (Map<String, Object> item : raw) {
                if (item.get("id") != null) {
                    camundaRestTemplate.delete(url("/authorization/" + encodePath(String.valueOf(item.get("id")))));
                    deleted = true;
                }
            }
            return deleted;
        } catch (RuntimeException e) {
            handle("delete Camunda authorization group=" + groupId
                    + " resourceType=" + resourceType + " resourceId=" + targetResourceId, e);
            return deleted;
        }
    }

    public boolean ensureGroupAuthorization(String groupId, int resourceType, String resourceId, List<String> permissions) {
        if (!properties.isEnabled()) {
            return false;
        }
        String targetResourceId = resourceId == null || resourceId.isBlank() ? "*" : resourceId;
        List<String> targetPermissions = permissions == null || permissions.isEmpty() ? List.of("READ") : permissions;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", 1);
        body.put("groupId", groupId);
        body.put("resourceType", resourceType);
        body.put("resourceId", targetResourceId);
        body.put("permissions", targetPermissions);
        try {
            String uri = UriComponentsBuilder.fromUriString(url("/authorization"))
                    .queryParam("type", 1)
                    .queryParam("groupIdIn", groupId)
                    .queryParam("resourceType", resourceType)
                    .queryParam("resourceId", targetResourceId)
                    .toUriString();
            List<Map<String, Object>> raw = camundaRestTemplate.exchange(uri, HttpMethod.GET, null, LIST_TYPE).getBody();
            if (authorizationHasPermissions(raw, targetPermissions)) {
                return true;
            }
            Optional<String> existingAuthorizationId = firstAuthorizationId(raw);
            if (existingAuthorizationId.isPresent()) {
                camundaRestTemplate.put(url("/authorization/" + encodePath(existingAuthorizationId.get())), body);
                return true;
            }
        } catch (RuntimeException e) {
            handle("check Camunda authorization group=" + groupId + " resourceType=" + resourceType + " resourceId=" + targetResourceId, e);
        }

        try {
            camundaRestTemplate.postForEntity(url("/authorization/create"), body, Void.class);
            return true;
        } catch (RuntimeException e) {
            handle("create Camunda authorization group=" + groupId + " resourceType=" + resourceType + " resourceId=" + targetResourceId, e);
            return false;
        }
    }

    private boolean authorizationHasPermissions(List<Map<String, Object>> authorizations, List<String> permissions) {
        if (authorizations == null || authorizations.isEmpty()) {
            return false;
        }
        Set<String> required = new LinkedHashSet<>(permissions);
        for (Map<String, Object> item : authorizations) {
            if (item.get("permissions") instanceof List<?> rawPermissions) {
                Set<String> existing = new LinkedHashSet<>();
                for (Object permission : rawPermissions) {
                    existing.add(String.valueOf(permission));
                }
                if (existing.containsAll(required)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Optional<String> firstAuthorizationId(List<Map<String, Object>> authorizations) {
        if (authorizations == null || authorizations.isEmpty()) {
            return Optional.empty();
        }
        for (Map<String, Object> item : authorizations) {
            if (item.get("id") != null) {
                return Optional.of(String.valueOf(item.get("id")));
            }
        }
        return Optional.empty();
    }

    public List<String> findFilterIdsByName(String name) {
        return findFilterIds("name", name);
    }

    public List<String> findFilterIdsByOwner(String owner) {
        return findFilterIds("owner", owner);
    }

    private List<String> findFilterIds(String param, String value) {
        if (!properties.isEnabled()) return List.of();
        try {
            String uri = UriComponentsBuilder.fromUriString(url("/filter"))
                    .queryParam(param, value)
                    .toUriString();
            List<Map<String, Object>> raw = camundaRestTemplate.exchange(uri, HttpMethod.GET, null, LIST_TYPE).getBody();
            if (raw == null || raw.isEmpty()) return List.of();
            List<String> ids = new ArrayList<>();
            for (Map<String, Object> item : raw) {
                if (item.get("id") != null) ids.add(String.valueOf(item.get("id")));
            }
            return ids;
        } catch (RuntimeException e) {
            handle("find Camunda filters " + param + "=" + value, e);
            return List.of();
        }
    }

    public void deleteFilter(String filterId) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            camundaRestTemplate.delete(url("/filter/" + encodePath(filterId)));
        } catch (RuntimeException e) {
            handle("delete Camunda filter " + filterId, e);
        }
    }

    public Optional<String> createTaskFilter(String name, Map<String, ?> query, Map<String, ?> filterProperties) {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("resourceType", "Task");
        body.put("query", query == null ? Map.of() : query);
        body.put("properties", filterProperties == null ? Map.of() : filterProperties);
        try {
            Map<String, Object> response = camundaRestTemplate.exchange(
                    url("/filter/create"), HttpMethod.POST, new HttpEntity<>(body), MAP_TYPE).getBody();
            return Optional.ofNullable(response == null ? null : response.get("id")).map(String::valueOf);
        } catch (RuntimeException e) {
            handle("create Camunda task filter " + name, e);
            return Optional.empty();
        }
    }

    public boolean updateTaskFilter(String filterId, String name, Map<String, ?> query, Map<String, ?> filterProperties) {
        if (!properties.isEnabled()) {
            return false;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("resourceType", "Task");
        body.put("query", query == null ? Map.of() : query);
        body.put("properties", filterProperties == null ? Map.of() : filterProperties);
        try {
            camundaRestTemplate.put(url("/filter/" + encodePath(filterId)), body);
            return true;
        } catch (RuntimeException e) {
            handle("update Camunda task filter " + name + " id=" + filterId, e);
            return false;
        }
    }

    public boolean ensureUserExists(String userId, String email, String firstName, String lastName, String initialPassword) {
        return ensureUserExists(userId, email, firstName, lastName, initialPassword, false);
    }

    public boolean ensureUserExists(String userId, String email, String firstName, String lastName,
                                    String initialPassword, boolean updatePasswordWhenExists) {
        if (!properties.isEnabled()) {
            return false;
        }
        String encodedUserId = encodePath(userId);
        boolean exists = false;
        try {
            camundaRestTemplate.getForEntity(url("/user/" + encodedUserId + "/profile"), Void.class);
            exists = true;
        } catch (HttpStatusCodeException e) {
            if (!e.getStatusCode().is4xxClientError()) {
                handle("read Camunda user " + userId, e);
                return false;
            }
        } catch (RuntimeException e) {
            handle("read Camunda user " + userId, e);
            return false;
        }

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", userId);
        profile.put("firstName", firstName == null ? "" : firstName);
        profile.put("lastName", lastName == null ? "" : lastName);
        profile.put("email", email == null ? userId : email);

        if (!exists) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("profile", profile);
            body.put("credentials", Map.of("password", initialPassword == null || initialPassword.isBlank()
                    ? "camunda" : initialPassword));
            try {
                camundaRestTemplate.postForEntity(url("/user/create"), body, Void.class);
                return true;
            } catch (RuntimeException e) {
                handle("create Camunda user " + userId, e);
                return false;
            }
        }

        try {
            camundaRestTemplate.put(url("/user/" + encodedUserId + "/profile"), profile);
            if (updatePasswordWhenExists) {
                updateUserPassword(userId, initialPassword);
            }
            return true;
        } catch (RuntimeException e) {
            handle("update Camunda user profile " + userId, e);
            return false;
        }
    }

    public boolean updateUserPassword(String userId, String password) {
        if (!properties.isEnabled()) {
            return false;
        }
        if (password == null || password.isBlank()) {
            return false;
        }
        Map<String, Object> body = Map.of("password", password);
        try {
            camundaRestTemplate.put(url("/user/" + encodePath(userId) + "/credentials"), body);
            return true;
        } catch (RuntimeException e) {
            handle("update Camunda user credentials " + userId, e);
            return false;
        }
    }

    public boolean ensureMembershipExists(String userId, String groupId) {
        if (!properties.isEnabled()) {
            return false;
        }
        String encodedUserId = encodePath(userId);
        String encodedGroupId = encodePath(groupId);
        try {
            String uri = UriComponentsBuilder.fromUriString(url("/group"))
                    .queryParam("member", userId)
                    .queryParam("id", groupId)
                    .toUriString();
            List<Map<String, Object>> raw = camundaRestTemplate.exchange(uri, HttpMethod.GET, null, LIST_TYPE).getBody();
            if (raw != null && !raw.isEmpty()) {
                return true;
            }
        } catch (RuntimeException e) {
            handle("check Camunda group membership user=" + userId + " group=" + groupId, e);
        }

        try {
            camundaRestTemplate.put(url("/group/" + encodedGroupId + "/members/" + encodedUserId), null);
            return true;
        } catch (RuntimeException e) {
            handle("create Camunda group membership user=" + userId + " group=" + groupId, e);
            return false;
        }
    }

    public Set<String> findMembershipGroupIds(String userId) {
        if (!properties.isEnabled()) {
            return Set.of();
        }
        try {
            String uri = UriComponentsBuilder.fromUriString(url("/group"))
                    .queryParam("member", userId)
                    .toUriString();
            List<Map<String, Object>> raw = camundaRestTemplate.exchange(uri, HttpMethod.GET, null, LIST_TYPE).getBody();
            if (raw == null || raw.isEmpty()) {
                return Set.of();
            }
            Set<String> result = new LinkedHashSet<>();
            for (Map<String, Object> item : raw) {
                if (item.get("id") != null) {
                    result.add(String.valueOf(item.get("id")));
                }
            }
            return result;
        } catch (RuntimeException e) {
            handle("read Camunda memberships for user=" + userId, e);
            return Set.of();
        }
    }

    public boolean removeMembershipIfExists(String userId, String groupId) {
        if (!properties.isEnabled()) {
            return false;
        }
        try {
            camundaRestTemplate.delete(url("/group/" + encodePath(groupId) + "/members/" + encodePath(userId)));
            return true;
        } catch (RuntimeException e) {
            handle("remove Camunda group membership user=" + userId + " group=" + groupId, e);
            return false;
        }
    }

    public Optional<String> startProcessByKey(String processKey, String businessKey, Map<String, ?> variables) {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("businessKey", businessKey);
        body.put("variables", CamundaVariable.variables(variables));
        try {
            Map<String, Object> response = camundaRestTemplate.exchange(
                    url("/process-definition/key/" + processKey + "/start"),
                    HttpMethod.POST, new HttpEntity<>(body), MAP_TYPE).getBody();
            return Optional.ofNullable(response == null ? null : response.get("id")).map(String::valueOf);
        } catch (RuntimeException e) {
            handle("start Camunda process " + processKey + " businessKey=" + businessKey, e);
            return Optional.empty();
        }
    }

    public boolean updateProcessInstanceBusinessKey(String processInstanceId, String businessKey) {
        if (!properties.isEnabled() || processInstanceId == null || processInstanceId.isBlank()
                || businessKey == null || businessKey.isBlank()) {
            return false;
        }
        Map<String, Object> body = Map.of("businessKey", businessKey);
        try {
            camundaRestTemplate.put(url("/process-instance/" + encodePath(processInstanceId) + "/business-key"), body);
            return true;
        } catch (RuntimeException e) {
            logSoftFailure("update Camunda process businessKey processInstanceId=" + processInstanceId, e);
            return false;
        }
    }

    public boolean hasActiveProcessInstance(String processKey, String businessKey) {
        if (!properties.isEnabled()) {
            return false;
        }
        String uri = UriComponentsBuilder.fromUriString(url("/process-instance"))
                .queryParam("processDefinitionKey", processKey)
                .queryParam("businessKey", businessKey)
                .queryParam("active", true)
                .toUriString();
        if (hasActiveProcessInstanceByUri(uri, "find active Camunda process " + processKey + " businessKey=" + businessKey)) {
            return true;
        }
        ProcessVariableQuery fallbackQuery = processVariableQueryFromBusinessKey(businessKey);
        if (fallbackQuery == null) {
            return false;
        }
        String fallbackUri = UriComponentsBuilder.fromUriString(url("/process-instance"))
                .queryParam("processDefinitionKey", processKey)
                .queryParam("variables", fallbackQuery.toCamundaQuery())
                .queryParam("active", true)
                .toUriString();
        return hasActiveProcessInstanceByUri(fallbackUri,
                "find active Camunda process " + processKey + " variable=" + fallbackQuery);
    }

    public boolean correlateMessage(String messageName, String businessKey, Map<String, ?> variables) {
        if (!properties.isEnabled()) {
            return false;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messageName", messageName);
        body.put("businessKey", businessKey);
        body.put("processVariables", CamundaVariable.variables(variables));
        try {
            camundaRestTemplate.postForEntity(url("/message"), body, Void.class);
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
        if (!properties.isEnabled()) {
            return List.of();
        }
        String uri = UriComponentsBuilder.fromUriString(url("/task"))
                .queryParam("processInstanceBusinessKey", businessKey)
                .queryParam("taskDefinitionKey", taskDefinitionKey)
                .queryParam("active", true)
                .toUriString();
        List<Map<String, Object>> byBusinessKey = findActiveTasksByUri(uri,
                "find Camunda task " + taskDefinitionKey + " businessKey=" + businessKey);
        if (!byBusinessKey.isEmpty()) {
            return byBusinessKey;
        }
        ProcessVariableQuery fallbackQuery = processVariableQueryFromBusinessKey(businessKey);
        if (fallbackQuery == null) {
            return List.of();
        }
        String fallbackUri = UriComponentsBuilder.fromUriString(url("/task"))
                .queryParam("processVariables", fallbackQuery.toCamundaQuery())
                .queryParam("taskDefinitionKey", taskDefinitionKey)
                .queryParam("active", true)
                .toUriString();
        return findActiveTasksByUri(fallbackUri,
                "find Camunda task " + taskDefinitionKey + " variable=" + fallbackQuery);
    }

    public List<Map<String, Object>> findActiveTasksByProcessInstanceId(String processInstanceId, String taskDefinitionKey) {
        if (!properties.isEnabled()) {
            return List.of();
        }
        String uri = UriComponentsBuilder.fromUriString(url("/task"))
                .queryParam("processInstanceId", processInstanceId)
                .queryParam("taskDefinitionKey", taskDefinitionKey)
                .queryParam("active", true)
                .toUriString();
        return findActiveTasksByUri(uri,
                "find Camunda task " + taskDefinitionKey + " processInstanceId=" + processInstanceId);
    }

    public List<Map<String, Object>> findActiveUserTasks(int maxResults) {
        if (!properties.isEnabled()) {
            return List.of();
        }
        String uri = UriComponentsBuilder.fromUriString(url("/task"))
                .queryParam("active", true)
                .queryParam("sortBy", "created")
                .queryParam("sortOrder", "desc")
                .queryParam("maxResults", Math.max(1, maxResults))
                .toUriString();
        return findActiveTasksByUri(uri, "find active Camunda user tasks");
    }

    public Map<String, Object> getTaskVariables(String taskId) {
        if (!properties.isEnabled()) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = camundaRestTemplate.exchange(
                    url("/task/" + encodePath(taskId) + "/variables"), HttpMethod.GET, null, MAP_TYPE).getBody();
            return raw == null ? Map.of() : new LinkedHashMap<>(raw);
        } catch (RuntimeException e) {
            handle("read Camunda task variables " + taskId, e);
            return Map.of();
        }
    }

    public boolean setTaskAssignee(String taskId, String userId) {
        if (!properties.isEnabled()) {
            return false;
        }
        Map<String, Object> body = Map.of("userId", userId);
        try {
            camundaRestTemplate.postForEntity(url("/task/" + encodePath(taskId) + "/assignee"), body, Void.class);
            return true;
        } catch (RuntimeException e) {
            handle("assign Camunda task " + taskId + " to " + userId, e);
            return false;
        }
    }

    public boolean ensureTaskUserAuthorization(String userId, String taskId) {
        if (!properties.isEnabled()) {
            return false;
        }
        try {
            String uri = UriComponentsBuilder.fromUriString(url("/authorization"))
                    .queryParam("type", 1)
                    .queryParam("userIdIn", userId)
                    .queryParam("resourceType", 7)
                    .queryParam("resourceId", taskId)
                    .toUriString();
            List<Map<String, Object>> raw = camundaRestTemplate.exchange(uri, HttpMethod.GET, null, LIST_TYPE).getBody();
            if (raw != null && !raw.isEmpty()) {
                return true;
            }
        } catch (RuntimeException e) {
            handle("check Camunda task authorization user=" + userId + " task=" + taskId, e);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", 1);
        body.put("userId", userId);
        body.put("resourceType", 7);
        body.put("resourceId", taskId);
        body.put("permissions", List.of("READ", "UPDATE", "TASK_WORK"));
        try {
            camundaRestTemplate.postForEntity(url("/authorization/create"), body, Void.class);
            return true;
        } catch (RuntimeException e) {
            handle("create Camunda task authorization user=" + userId + " task=" + taskId, e);
            return false;
        }
    }

    private boolean hasActiveProcessInstanceByUri(String uri, String operation) {
        try {
            List<Map<String, Object>> raw = camundaRestTemplate.exchange(uri, HttpMethod.GET, null, LIST_TYPE).getBody();
            return raw != null && !raw.isEmpty();
        } catch (RuntimeException e) {
            handle(operation, e);
            return false;
        }
    }

    private List<Map<String, Object>> findActiveTasksByUri(String uri, String operation) {
        try {
            List<Map<String, Object>> raw = camundaRestTemplate.exchange(uri, HttpMethod.GET, null, LIST_TYPE).getBody();
            return raw == null || raw.isEmpty() ? List.of() : raw;
        } catch (RuntimeException e) {
            handle(operation, e);
            return List.of();
        }
    }

    private ProcessVariableQuery processVariableQueryFromBusinessKey(String businessKey) {
        if (businessKey == null || businessKey.isBlank()) {
            return null;
        }
        if (businessKey.startsWith("application:")) {
            return new ProcessVariableQuery("applicationId", businessKey.substring("application:".length()));
        }
        if (businessKey.startsWith("vacancy:")) {
            return new ProcessVariableQuery("vacancyId", businessKey.substring("vacancy:".length()));
        }
        return null;
    }

    private record ProcessVariableQuery(String name, String value) {
        String toCamundaQuery() {
            return name + "_eq_" + value;
        }
    }

    public boolean taskHasCandidateGroup(String taskId, String expectedGroup) {
        if (!properties.isEnabled()) {
            return false;
        }
        try {
            List<Map<String, Object>> raw = camundaRestTemplate.exchange(
                    url("/task/" + taskId + "/identity-links"), HttpMethod.GET, null, LIST_TYPE).getBody();
            if (raw == null || raw.isEmpty()) {
                return false;
            }
            for (Map<String, Object> item : raw) {
                if (expectedGroup.equals(String.valueOf(item.get("groupId")))
                        && "candidate".equals(String.valueOf(item.get("type")))) {
                    return true;
                }
            }
            return false;
        } catch (HttpStatusCodeException e) {
            if (isMissingTask(e)) {
                logSoftFailure("read Camunda task identity links " + taskId, e);
                return false;
            }
            handle("read Camunda task identity links " + taskId, e);
            return false;
        } catch (RuntimeException e) {
            handle("read Camunda task identity links " + taskId, e);
            return false;
        }
    }

    public boolean completeTask(String taskId, Map<String, ?> variables) {
        if (!properties.isEnabled()) {
            return false;
        }
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

    private String encodePath(String value) {
        return UriUtils.encodePathSegment(value, StandardCharsets.UTF_8);
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
            log.warn("Cannot {}: status={}, body={}", action, statusCodeException.getStatusCode(), statusCodeException.getResponseBodyAsString());
        } else {
            log.warn("Cannot {}: {}", action, e.getMessage());
        }
    }

    private boolean isMessageCorrelationMiss(HttpStatusCodeException e) {
        String body = e.getResponseBodyAsString();
        return e.getStatusCode().is4xxClientError()
                && (body.contains("MismatchingMessageCorrelationException")
                || body.contains("Cannot correlate message"));
    }

    private boolean isConcurrentTaskUpdate(HttpStatusCodeException e) {
        String body = e.getResponseBodyAsString();
        return e.getStatusCode().is5xxServerError()
                && (body.contains("updated by another transaction concurrently")
                || body.contains("ENGINE-03005"));
    }

    private boolean isMissingTask(HttpStatusCodeException e) {
        String body = e.getResponseBodyAsString();
        return e.getStatusCode().is5xxServerError()
                && body.contains("Cannot find task with id")
                && body.contains("task is null");
    }
}
