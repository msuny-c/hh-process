package ru.itmo.hhprocess.camunda;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.enums.ResponseType;
import ru.itmo.hhprocess.exception.ApiException;
import ru.itmo.hhprocess.service.TimeoutService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.camunda.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CamundaExternalTaskWorker {

    private static final String TOPIC_AUTO_SCREEN = "application-auto-screening";
    private static final String TOPIC_NOTIFY = "notification-send";
    private static final String TOPIC_TIMEOUT = "timeout-close-expired";
    private static final String TOPIC_VACANCY_CLOSE = "vacancy-close-applications";
    private static final String TOPIC_ROLLBACK = "transaction-rollback";
    private static final String TOPIC_ADMIN_INTERVIEW_RESET = "admin-interview-reset";
    private static final String APPLICATION_TRANSACTION_FAILED = "APPLICATION_TX_FAILED";
    private static final String VACANCY_TRANSACTION_FAILED = "VACANCY_TX_FAILED";
    private static final String ADMIN_RESET_FAILED = "ADMIN_RESET_FAILED";

    private final CamundaRestClient camundaRestClient;
    private final TimeoutService timeoutService;
    private final CamundaProcessAdapterService adapterService;

    @Scheduled(fixedDelayString = "${app.camunda.worker.poll-interval-ms:3000}", initialDelayString = "${app.camunda.worker.initial-delay-ms:10000}")
    public void poll() {
        if (!camundaRestClient.isEnabled()) {
            return;
        }
        List<Map<String, Object>> tasks = camundaRestClient.fetchAndLockExternalTasks(
                List.of(TOPIC_AUTO_SCREEN, TOPIC_NOTIFY, TOPIC_TIMEOUT, TOPIC_VACANCY_CLOSE, TOPIC_ROLLBACK,
                        TOPIC_ADMIN_INTERVIEW_RESET)
        );
        for (Map<String, Object> task : tasks) {
            handleTask(task);
        }
    }

    private void handleTask(Map<String, Object> task) {
        String taskId = String.valueOf(task.get("id"));
        String topic = String.valueOf(task.get("topicName"));
        String activityId = String.valueOf(task.get("activityId"));
        try {
            Map<String, Object> variables = switch (topic) {
                case TOPIC_AUTO_SCREEN -> adapterService.autoScreen(readRequiredUuid(task, "applicationId"));
                case TOPIC_NOTIFY -> handleNotificationBackedTask(activityId, task);
                case TOPIC_TIMEOUT -> handleTimeoutTask(activityId, task);
                case TOPIC_VACANCY_CLOSE -> handleVacancyCloseTask(activityId, task);
                case TOPIC_ROLLBACK -> handleRollbackTask(activityId, task);
                case TOPIC_ADMIN_INTERVIEW_RESET -> adapterService.resetInterviewByAdmin(
                        readRequiredUuid(task, "interviewId"),
                        readRequiredUuid(task, "adminUserId"),
                        stringValue(task, "resetReason"));
                default -> Map.of("ignored", true, "topic", topic);
            };
            camundaRestClient.completeExternalTask(taskId, variables);
        } catch (Exception e) {
            log.error("Camunda external task failed; taskId={}, topic={}", taskId, topic, e);
            if (shouldRouteToBpmnRollback(e) && throwRollbackBpmnError(taskId, activityId, task, e)) {
                return;
            }
            camundaRestClient.failExternalTask(taskId, e.getMessage(), stackTraceToString(e));
        }
    }

    private Map<String, Object> handleNotificationBackedTask(String activityId, Map<String, Object> task) {
        UUID applicationId = readRequiredUuid(task, "applicationId");
        return switch (activityId) {
            case "NotifyScreeningFailed" -> adapterService.notifyScreeningFailed(applicationId);
            case "NotifyRecruiter" -> adapterService.notifyRecruiter(applicationId);
            case "PersistRejection" -> {
                Map<String, Object> variables = new java.util.LinkedHashMap<>(
                        adapterService.rejectApplication(applicationId, stringValue(task, "recruiterComment")));
                variables.putAll(adapterService.notifyApplicationRejected(applicationId));
                yield variables;
            }
            case "PersistRejectionToDb" -> adapterService.rejectApplication(applicationId, stringValue(task, "recruiterComment"));
            case "NotifyRejection" -> adapterService.notifyApplicationRejected(applicationId);
            case "PersistInvitation" -> {
                String invitationMessage = stringValue(task, "invitationMessage");
                Map<String, Object> variables = new java.util.LinkedHashMap<>(adapterService.persistInvitation(
                        applicationId,
                        invitationMessage,
                        adapterService.scheduledAtOrDefault(readValue(task, "scheduledAt")),
                        adapterService.durationOrDefault(readValue(task, "durationMinutes"))
                ));
                variables.putAll(adapterService.notifyInvitation(applicationId, invitationMessage));
                yield variables;
            }
            case "PersistInvitationToDb" -> adapterService.persistInvitation(
                    applicationId,
                    stringValue(task, "invitationMessage"),
                    adapterService.scheduledAtOrDefault(readValue(task, "scheduledAt")),
                    adapterService.durationOrDefault(readValue(task, "durationMinutes"))
            );
            case "NotifyInvitation" -> adapterService.notifyInvitation(applicationId, stringValue(task, "invitationMessage"));
            case "PersistCandidateResponse" -> {
                Map<String, Object> variables = new java.util.LinkedHashMap<>(adapterService.persistCandidateResponse(
                        applicationId,
                        ResponseType.valueOf(stringValue(task, "responseType")),
                        stringValue(task, "responseMessage")
                ));
                variables.putAll(adapterService.notifyCandidateResponse(applicationId));
                yield variables;
            }
            case "PersistCandidateResponseToDb" -> adapterService.persistCandidateResponse(
                    applicationId,
                    ResponseType.valueOf(stringValue(task, "responseType")),
                    stringValue(task, "responseMessage")
            );
            case "NotifyCandidateResponse" -> adapterService.notifyCandidateResponse(applicationId);
            default -> Map.of("adapterCompleted", true, "activityId", activityId);
        };
    }

    private Map<String, Object> handleTimeoutTask(String activityId, Map<String, Object> task) {
        if ("CloseByTimeout".equals(activityId)) {
            return adapterService.closeByTimeout(readRequiredUuid(task, "applicationId"));
        }
        int closedCount = timeoutService.runCloseExpired();
        return Map.of("closedCount", closedCount, "timeoutScanCompleted", true);
    }

    private Map<String, Object> handleVacancyCloseTask(String activityId, Map<String, Object> task) {
        UUID vacancyId = readRequiredUuid(task, "vacancyId");
        String closeReason = stringValue(task, "closeReason");
        return switch (activityId) {
            case "CloseVacancyAndApplicationsToDb" -> adapterService.closeVacancyApplicationsInDb(vacancyId, closeReason);
            case "NotifyVacancyClosedCandidates" -> adapterService.notifyVacancyClosedCandidates(vacancyId);
            default -> adapterService.closeVacancyApplications(vacancyId, closeReason);
        };
    }

    private Map<String, Object> handleRollbackTask(String activityId, Map<String, Object> task) {
        return switch (activityId) {
            case "RollbackApplicationTransaction" -> adapterService.rollbackApplicationTransaction(
                    readRequiredUuid(task, "applicationId"), stringValue(task, "rollbackReason"));
            case "RollbackVacancyTransaction" -> adapterService.rollbackVacancyTransaction(
                    readRequiredUuid(task, "vacancyId"), stringValue(task, "rollbackReason"));
            case "RollbackAdminReset" -> adapterService.rollbackApplicationTransaction(
                    readRequiredUuid(task, "applicationId"), stringValue(task, "rollbackReason"));
            default -> Map.of("rollbackIgnored", true, "activityId", activityId);
        };
    }

    private boolean throwRollbackBpmnError(String taskId, String activityId, Map<String, Object> task, Exception e) {
        String errorCode = switch (activityId) {
            case "CloseActiveApplications", "CloseVacancyAndApplicationsToDb", "NotifyVacancyClosedCandidates" -> VACANCY_TRANSACTION_FAILED;
            case "ResetInterviewTransaction" -> ADMIN_RESET_FAILED;
            default -> APPLICATION_TRANSACTION_FAILED;
        };
        Object applicationId = readValue(task, "applicationId");
        Object vacancyId = readValue(task, "vacancyId");
        Map<String, Object> variables = new java.util.LinkedHashMap<>();
        if (applicationId != null) {
            variables.put("applicationId", applicationId);
        }
        if (vacancyId != null) {
            variables.put("vacancyId", vacancyId);
        }
        variables.put("rollbackReason", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        return camundaRestClient.throwBpmnErrorExternalTask(taskId, errorCode, e.getMessage(), variables);
    }

    private boolean shouldRouteToBpmnRollback(Exception e) {
        return e instanceof ApiException || e instanceof IllegalArgumentException;
    }

    private UUID readRequiredUuid(Map<String, Object> task, String name) {
        UUID value = readUuid(task, name);
        if (value == null) {
            throw new IllegalArgumentException("Camunda external task variable is required: " + name);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private UUID readUuid(Map<String, Object> task, String name) {
        Object value = readValue(task, name);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return UUID.fromString(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private Object readValue(Map<String, Object> task, String name) {
        Object variablesRaw = task.get("variables");
        if (!(variablesRaw instanceof Map<?, ?> variables)) {
            return null;
        }
        return CamundaVariable.readValue((Map<String, Object>) variables, name);
    }

    private String stringValue(Map<String, Object> task, String name) {
        Object value = readValue(task, name);
        return value == null ? "" : String.valueOf(value);
    }

    private static String stackTraceToString(Exception e) {
        java.io.StringWriter stringWriter = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(stringWriter));
        return stringWriter.toString();
    }
}
