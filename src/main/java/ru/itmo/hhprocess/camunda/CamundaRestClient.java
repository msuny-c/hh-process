package ru.itmo.hhprocess.camunda;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class CamundaRestClient {

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
            camundaRestTemplate.getForEntity(url("/version"), Map.class);
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
            body.add("enable-duplicate-filtering", "true");
            body.add("deploy-changed-only", "true");
            int index = 0;
            for (Map.Entry<String, Resource> entry : resources.entrySet()) {
                String partName = "resource-" + index++;
                body.add(partName, namedResource(partName, entry.getKey(), entry.getValue()));
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            ResponseEntity<Map> response = camundaRestTemplate.postForEntity(
                    url("/deployment/create"), new HttpEntity<>(body, headers), Map.class);
            Object id = response.getBody() == null ? null : response.getBody().get("id");
            return Optional.ofNullable(id).map(String::valueOf);
        } catch (RuntimeException e) {
            handle("deploy BPMN/resources", e);
            return Optional.empty();
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
            ResponseEntity<Map> response = camundaRestTemplate.postForEntity(
                    url("/process-definition/key/" + processKey + "/start"), body, Map.class);
            Object id = response.getBody() == null ? null : response.getBody().get("id");
            return Optional.ofNullable(id).map(String::valueOf);
        } catch (RuntimeException e) {
            handle("start Camunda process " + processKey + " businessKey=" + businessKey, e);
            return Optional.empty();
        }
    }

    public boolean hasActiveProcessInstance(String processKey, String businessKey) {
        if (!properties.isEnabled()) {
            return false;
        }
        try {
            String uri = UriComponentsBuilder.fromHttpUrl(url("/process-instance"))
                    .queryParam("processDefinitionKey", processKey)
                    .queryParam("businessKey", businessKey)
                    .queryParam("active", true)
                    .toUriString();
            ResponseEntity<List> response = camundaRestTemplate.exchange(uri, HttpMethod.GET, null, List.class);
            List<?> raw = response.getBody();
            return raw != null && !raw.isEmpty();
        } catch (RuntimeException e) {
            handle("find active Camunda process " + processKey + " businessKey=" + businessKey, e);
            return false;
        }
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
            camundaRestTemplate.postForEntity(url("/message"), body, Map.class);
            return true;
        } catch (RuntimeException e) {
            handle("correlate Camunda message " + messageName + " businessKey=" + businessKey, e);
            return false;
        }
    }

    public List<Map<String, Object>> findActiveTasks(String businessKey, String taskDefinitionKey) {
        if (!properties.isEnabled()) {
            return List.of();
        }
        try {
            String uri = UriComponentsBuilder.fromHttpUrl(url("/task"))
                    .queryParam("processInstanceBusinessKey", businessKey)
                    .queryParam("taskDefinitionKey", taskDefinitionKey)
                    .queryParam("active", true)
                    .toUriString();
            ResponseEntity<List> response = camundaRestTemplate.exchange(uri, HttpMethod.GET, null, List.class);
            List<?> raw = response.getBody();
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : raw) {
                if (item instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked") Map<String, Object> typed = (Map<String, Object>) map;
                    result.add(typed);
                }
            }
            return result;
        } catch (RuntimeException e) {
            handle("find Camunda task " + taskDefinitionKey + " businessKey=" + businessKey, e);
            return List.of();
        }
    }

    public boolean taskHasCandidateGroup(String taskId, String expectedGroup) {
        if (!properties.isEnabled()) {
            return false;
        }
        try {
            ResponseEntity<List> response = camundaRestTemplate.exchange(
                    url("/task/" + taskId + "/identity-links"), HttpMethod.GET, null, List.class);
            List<?> raw = response.getBody();
            if (raw == null || raw.isEmpty()) {
                return false;
            }
            for (Object item : raw) {
                if (item instanceof Map<?, ?> map
                        && expectedGroup.equals(String.valueOf(map.get("groupId")))
                        && "candidate".equals(String.valueOf(map.get("type")))) {
                    return true;
                }
            }
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
        } catch (RuntimeException e) {
            handle("complete Camunda task " + taskId, e);
            return false;
        }
    }

    public List<Map<String, Object>> fetchAndLockExternalTasks(List<String> topics) {
        if (!properties.isEnabled() || topics.isEmpty()) {
            return List.of();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workerId", properties.getWorker().getId());
        body.put("maxTasks", properties.getWorker().getMaxTasks());
        body.put("usePriority", true);
        body.put("asyncResponseTimeout", properties.getWorker().getAsyncResponseTimeoutMs());
        List<Map<String, Object>> topicBody = topics.stream()
                .map(topic -> Map.<String, Object>of(
                        "topicName", topic,
                        "lockDuration", properties.getWorker().getLockDurationMs(),
                        "variables", List.of(
                                "applicationId",
                                "interviewId",
                                "vacancyId",
                                "candidateUserId",
                                "recruiterUserId",
                                "adminUserId",
                                "vacancyTitle",
                                "screeningPassed",
                                "status",
                                "decision",
                                "recruiterComment",
                                "invitationMessage",
                                "scheduledAt",
                                "durationMinutes",
                                "responseType",
                                "responseMessage",
                                "closeReason",
                                "resetReason",
                                "rollbackReason")))
                .toList();
        body.put("topics", topicBody);
        try {
            ResponseEntity<List> response = camundaRestTemplate.postForEntity(url("/external-task/fetchAndLock"), body, List.class);
            List<?> raw = response.getBody();
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : raw) {
                if (item instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked") Map<String, Object> typed = (Map<String, Object>) map;
                    result.add(typed);
                }
            }
            return result;
        } catch (RuntimeException e) {
            handle("fetch Camunda external tasks", e);
            return List.of();
        }
    }

    public void completeExternalTask(String externalTaskId, Map<String, ?> variables) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workerId", properties.getWorker().getId());
        body.put("variables", CamundaVariable.variables(variables));
        try {
            camundaRestTemplate.postForEntity(url("/external-task/" + externalTaskId + "/complete"), body, Void.class);
        } catch (RuntimeException e) {
            handle("complete Camunda external task " + externalTaskId, e);
        }
    }

    public boolean throwBpmnErrorExternalTask(String externalTaskId, String errorCode, String message, Map<String, ?> variables) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workerId", properties.getWorker().getId());
        body.put("errorCode", errorCode);
        body.put("errorMessage", message == null ? "External task transaction failed" : message);
        body.put("variables", CamundaVariable.variables(variables));
        try {
            camundaRestTemplate.postForEntity(url("/external-task/" + externalTaskId + "/bpmnError"), body, Void.class);
            return true;
        } catch (RuntimeException e) {
            handle("throw Camunda BPMN error for external task " + externalTaskId, e);
            return false;
        }
    }

    public void failExternalTask(String externalTaskId, String message, String details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workerId", properties.getWorker().getId());
        body.put("errorMessage", message == null ? "External task failed" : message);
        body.put("errorDetails", details == null ? "" : details);
        body.put("retries", 3);
        body.put("retryTimeout", 10000);
        try {
            camundaRestTemplate.postForEntity(url("/external-task/" + externalTaskId + "/failure"), body, Void.class);
        } catch (RuntimeException e) {
            handle("fail Camunda external task " + externalTaskId, e);
        }
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
        if (e instanceof HttpStatusCodeException statusCodeException) {
            log.warn("Cannot {}: status={}, body={}", action, statusCodeException.getStatusCode(), statusCodeException.getResponseBodyAsString());
        } else {
            log.warn("Cannot {}: {}", action, e.getMessage());
        }
        if (properties.isFailOnError()) {
            throw e;
        }
    }
}
