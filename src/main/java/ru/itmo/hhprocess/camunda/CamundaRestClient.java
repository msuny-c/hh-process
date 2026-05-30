package ru.itmo.hhprocess.camunda;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import ru.itmo.hhprocess.messaging.dto.ApplicationScreenedEvent;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.camunda.enabled", havingValue = "true")
public class CamundaRestClient {
    private final CamundaProperties properties;
    private final ObjectMapper objectMapper;

    private RestClient restClient() {
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getBaseUrl());
        if (StringUtils.hasText(properties.getUsername())) {
            builder.defaultHeaders(headers -> headers.setBasicAuth(properties.getUsername(), properties.getPassword() == null ? "" : properties.getPassword()));
        }
        return builder.build();
    }

    public List<CamundaExternalTask> fetchAndLock(List<String> topics) {
        if (topics.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> topicRequests = topics.stream()
                .map(topic -> {
                    Map<String, Object> request = new LinkedHashMap<>();
                    request.put("topicName", topic);
                    request.put("lockDuration", properties.getLockDurationMs());
                    request.put("variables", CamundaVariables.ALL);
                    return request;
                })
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workerId", properties.getWorkerId());
        body.put("maxTasks", properties.getMaxTasks());
        body.put("usePriority", true);
        body.put("topics", topicRequests);
        CamundaExternalTask[] tasks = restClient().post()
                .uri("/external-task/fetchAndLock")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(CamundaExternalTask[].class);
        return tasks == null ? List.of() : List.of(tasks);
    }

    public void complete(String taskId, Map<String, Object> variables) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workerId", properties.getWorkerId());
        body.put("variables", toCamundaVariables(variables));
        restClient().post()
                .uri("/external-task/{id}/complete", taskId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    public void fail(String taskId, Exception exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workerId", properties.getWorkerId());
        body.put("errorMessage", exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
        body.put("errorDetails", exception.toString());
        body.put("retries", properties.getFailureRetries());
        body.put("retryTimeout", properties.getFailureRetryTimeoutMs());
        restClient().post()
                .uri("/external-task/{id}/failure", taskId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }


    public void completeUserTaskByApplicationId(String taskDefinitionKey, UUID applicationId, Map<String, Object> variables) {
        String taskId = findActiveTaskId(taskDefinitionKey, applicationId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("variables", toCamundaVariables(variables));
        restClient().post()
                .uri("/task/{id}/complete", taskId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    private String findActiveTaskId(String taskDefinitionKey, UUID applicationId) {
        Map<String, Object> variableFilter = new LinkedHashMap<>();
        variableFilter.put("name", CamundaVariables.APPLICATION_ID);
        variableFilter.put("operator", "eq");
        variableFilter.put("value", applicationId.toString());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("active", true);
        body.put("taskDefinitionKey", taskDefinitionKey);
        body.put("processVariables", List.of(variableFilter));

        List<?> tasks = restClient().post()
                .uri("/task")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(List.class);
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalStateException("Active Camunda user task " + taskDefinitionKey + " for application " + applicationId + " not found");
        }
        Object first = tasks.get(0);
        if (!(first instanceof Map<?, ?> task)) {
            throw new IllegalStateException("Unexpected Camunda task response");
        }
        Object id = task.get("id");
        if (id == null) {
            throw new IllegalStateException("Camunda task id is missing");
        }
        return id.toString();
    }

    public void correlateScreeningCompleted(ApplicationScreenedEvent event) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put(CamundaVariables.APPLICATION_ID, event.applicationId().toString());
        variables.put(CamundaVariables.SCREENING_PASSED, event.passed());
        variables.put(CamundaVariables.SCREENING_SCORE, event.score());
        variables.put(CamundaVariables.MATCHED_SKILLS_JSON, writeJson(event.matchedSkills()));
        variables.put(CamundaVariables.SCREENING_DETAILS_JSON, writeJson(event.detailsJson()));
        variables.put(CamundaVariables.SCREENING_STARTED_AT, event.screeningStartedAt().toString());
        variables.put(CamundaVariables.SCREENING_FINISHED_AT, event.processedAt().toString());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messageName", properties.getScreeningMessageName());
        body.put("correlationKeys", Map.of(CamundaVariables.APPLICATION_ID, variable(event.applicationId().toString())));
        body.put("processVariables", toCamundaVariables(variables));
        restClient().post()
                .uri("/message")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    public String startCandidateApplication(UUID vacancyId, String candidateUserId, String resumeText, String coverLetter) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put(CamundaVariables.VACANCY_ID, vacancyId.toString());
        variables.put(CamundaVariables.CANDIDATE_USER_ID, candidateUserId);
        variables.put(CamundaVariables.CANDIDATE_EMAIL, candidateUserId);
        variables.put(CamundaVariables.RESUME_TEXT, resumeText);
        variables.put(CamundaVariables.COVER_LETTER, coverLetter);
        return startProcessByKey(properties.getProcessKey(), "application-start-" + UUID.randomUUID(), variables);
    }

    public String startProcessByKey(String processKey, String businessKey, Map<String, Object> variables) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("businessKey", businessKey == null ? processKey + "-" + UUID.randomUUID() : businessKey);
        body.put("variables", toCamundaVariables(variables));
        Map<?, ?> response = restClient().post()
                .uri("/process-definition/key/{key}/start", processKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        Object id = response == null ? null : response.get("id");
        return id == null ? null : id.toString();
    }

    public Map<String, Object> toCamundaVariables(Map<String, Object> variables) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (variables == null) {
            return result;
        }
        variables.forEach((key, value) -> result.put(key, variable(value)));
        return result;
    }

    private Map<String, Object> variable(Object value) {
        Map<String, Object> variable = new LinkedHashMap<>();
        variable.put("value", normalizeValue(value));
        variable.put("type", type(value));
        return variable;
    }

    private Object normalizeValue(Object value) {
        if (value instanceof UUID uuid) {
            return uuid.toString();
        }
        if (value instanceof Instant instant) {
            return instant.toString();
        }
        return value;
    }

    private String type(Object value) {
        if (value instanceof Boolean) {
            return "Boolean";
        }
        if (value instanceof Integer) {
            return "Integer";
        }
        if (value instanceof Long) {
            return "Long";
        }
        return "String";
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? new ArrayList<>() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
