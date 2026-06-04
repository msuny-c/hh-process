package ru.itmo.hhprocess.camunda;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.enums.ResponseType;
import ru.itmo.hhprocess.exception.ApiException;
import ru.itmo.hhprocess.service.TimeoutBatchProcessor;
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
    private static final String TOPIC_APPLICATION_PERSISTENCE = "application-persistence";
    private static final String TOPIC_APPLICATION_NOTIFICATION = "application-notification";
    private static final String TOPIC_APPLICATION_MESSAGE = "application-message";
    private static final String TOPIC_FORM_VALIDATION = "form-validation";
    private static final String TOPIC_TIMEOUT = "timeout-close-expired";
    private static final String TOPIC_VACANCY_CREATE = "vacancy-create";
    private static final String TOPIC_VACANCY_CLOSE = "vacancy-close-applications";
    private static final String TOPIC_VACANCY_STATUS_UPDATE = "vacancy-status-update";
    private static final String TOPIC_INTERVIEW_CANCEL = "interview-cancel";
    private static final String TOPIC_ROLLBACK = "transaction-rollback";
    private static final String TOPIC_ADMIN_INTERVIEW_RESET = "admin-interview-reset";
    private static final String TOPIC_UI_QUERY = "ui-query";
    private static final String APPLICATION_TRANSACTION_FAILED = "APPLICATION_TX_FAILED";
    private static final String VACANCY_TRANSACTION_FAILED = "VACANCY_TX_FAILED";
    private static final String ADMIN_RESET_FAILED = "ADMIN_RESET_FAILED";
    private static final String FORM_VALIDATION_FAILED = "FORM_VALIDATION_FAILED";

    private final CamundaRestClient camundaRestClient;
    private final TimeoutService timeoutService;
    private final TimeoutBatchProcessor timeoutBatchProcessor;
    private final CamundaProcessAdapterService adapterService;

    @Scheduled(fixedDelayString = "${app.camunda.worker.poll-interval-ms:3000}", initialDelayString = "${app.camunda.worker.initial-delay-ms:10000}")
    public void poll() {
        if (!camundaRestClient.isEnabled()) {
            return;
        }
        List<Map<String, Object>> tasks = camundaRestClient.fetchAndLockExternalTasks(
                List.of(TOPIC_AUTO_SCREEN, TOPIC_NOTIFY, TOPIC_APPLICATION_PERSISTENCE, TOPIC_APPLICATION_NOTIFICATION, TOPIC_APPLICATION_MESSAGE,
                        TOPIC_FORM_VALIDATION, TOPIC_TIMEOUT, TOPIC_VACANCY_CREATE, TOPIC_VACANCY_CLOSE,
                        TOPIC_VACANCY_STATUS_UPDATE, TOPIC_INTERVIEW_CANCEL, TOPIC_ROLLBACK, TOPIC_ADMIN_INTERVIEW_RESET, TOPIC_UI_QUERY)
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
                case TOPIC_NOTIFY, TOPIC_APPLICATION_PERSISTENCE, TOPIC_APPLICATION_NOTIFICATION, TOPIC_APPLICATION_MESSAGE ->
                        handleNotificationBackedTask(activityId, task);
                case TOPIC_FORM_VALIDATION -> handleFormValidationTask(activityId, task);
                case TOPIC_TIMEOUT -> handleTimeoutTask(activityId, task);
                case TOPIC_VACANCY_CREATE -> handleVacancyCreateTask(activityId, task);
                case TOPIC_VACANCY_CLOSE -> handleVacancyCloseTask(activityId, task);
                case TOPIC_VACANCY_STATUS_UPDATE -> handleVacancyStatusUpdateTask(activityId, task);
                case TOPIC_INTERVIEW_CANCEL -> handleInterviewCancelTask(activityId, task);
                case TOPIC_ROLLBACK -> handleRollbackTask(activityId, task);
                case TOPIC_ADMIN_INTERVIEW_RESET -> handleAdminInterviewResetTask(activityId, task);
                case TOPIC_UI_QUERY -> handleUiQueryTask(activityId, task);
                default -> Map.of("ignored", true, "topic", topic);
            };
            camundaRestClient.completeExternalTask(taskId, variables);
        } catch (CamundaFormValidationException e) {
            log.warn("Camunda form validation failed; taskId={}, topic={}, activityId={}, message={}",
                    taskId, topic, activityId, e.getMessage());
            if (throwFormValidationBpmnError(taskId, task, e)) {
                return;
            }
            camundaRestClient.failExternalTask(taskId, e.getMessage(), stackTraceToString(e));
        } catch (Exception e) {
            log.error("Camunda external task failed; taskId={}, topic={}", taskId, topic, e);
            if (shouldRouteToBpmnRollback(e) && throwRollbackBpmnError(taskId, activityId, task, e)) {
                return;
            }
            camundaRestClient.failExternalTask(taskId, e.getMessage(), stackTraceToString(e));
        }
    }

    private Map<String, Object> handleFormValidationTask(String activityId, Map<String, Object> task) {
        UUID applicationId = readUuid(task, "applicationId");
        return switch (activityId) {
            case "ValidateApplyToVacancyForm" -> adapterService.validateApplyToVacancyForm(
                    applicationId,
                    readUuid(task, "vacancyId"),
                    readUuid(task, "candidateUserId"),
                    stringValue(task, "starterUserId"),
                    stringValue(task, "resumeText"),
                    stringValue(task, "coverLetter")
            );
            case "ValidateRecruiterDecisionForm" -> adapterService.validateRecruiterDecisionForm(
                    required(applicationId, "applicationId"),
                    stringValue(task, "decision"),
                    stringValue(task, "recruiterComment")
            );
            case "ValidateInvitationForm" -> adapterService.validateInvitationForm(
                    required(applicationId, "applicationId"),
                    stringValue(task, "invitationMessage"),
                    adapterService.requiredScheduledAt(readValue(task, "scheduledAt")),
                    adapterService.requiredDurationMinutes(readValue(task, "durationMinutes"))
            );
            case "ValidateCandidateResponseForm" -> adapterService.validateCandidateResponseForm(
                    required(applicationId, "applicationId"),
                    stringValue(task, "responseType"),
                    stringValue(task, "responseMessage")
            );
            default -> Map.of("formValidationIgnored", true, "activityId", activityId);
        };
    }

    private Map<String, Object> handleNotificationBackedTask(String activityId, Map<String, Object> task) {
        UUID applicationId = readUuid(task, "applicationId");
        return switch (activityId) {
            case "CreateApplicationFromForm" -> adapterService.createApplicationFromCamundaForm(
                    readUuid(task, "applicationId"),
                    readRequiredUuid(task, "vacancyId"),
                    readUuid(task, "candidateUserId"),
                    stringValue(task, "starterUserId"),
                    stringValue(task, "resumeText"),
                    stringValue(task, "coverLetter"),
                    task.get("processInstanceId") == null ? "" : String.valueOf(task.get("processInstanceId"))
            );
            case "NotifyScreeningFailed" -> adapterService.notifyScreeningFailed(applicationId);
            case "NotifyRecruiter" -> adapterService.notifyRecruiter(applicationId);
            case "PersistRejection" -> {
                Map<String, Object> variables = new java.util.LinkedHashMap<>(
                        adapterService.rejectApplication(applicationId, stringValue(task, "recruiterComment")));
                variables.putAll(adapterService.notifyApplicationRejected(applicationId));
                yield variables;
            }
            case "ValidateRejectionAllowed" -> adapterService.validateRejectionAllowed(applicationId, stringValue(task, "recruiterComment"));
            case "CancelRejectionInterviewIfAny" -> adapterService.cancelRejectionInterviewIfAny(applicationId, stringValue(task, "recruiterComment"));
            case "MarkApplicationRejected" -> adapterService.markApplicationRejected(applicationId, stringValue(task, "recruiterComment"));
            case "RecordRejectionHistory" -> adapterService.recordRejectionHistory(applicationId);
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
            case "PersistInvitationToDb" -> adapterService.saveInvitationToDb(
                    applicationId,
                    stringValue(task, "invitationMessage")
            );
            case "CreateInvitationInterview" -> adapterService.createInvitationInterview(
                    applicationId,
                    stringValue(task, "invitationMessage"),
                    adapterService.requiredScheduledAt(readValue(task, "scheduledAt")),
                    adapterService.requiredDurationMinutes(readValue(task, "durationMinutes"))
            );
            case "ReserveInvitationSlot" -> adapterService.reserveInvitationSlot(
                    applicationId,
                    readRequiredUuid(task, "interviewId"),
                    adapterService.requiredScheduledAt(readValue(task, "scheduledAt")),
                    adapterService.requiredDurationMinutes(readValue(task, "durationMinutes"))
            );
            case "RecordInvitationHistory" -> adapterService.recordInvitationHistory(applicationId);
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
            case "CheckInvitationStillActive" -> adapterService.checkInvitationStillActive(applicationId);
            case "SaveCandidateResponse" -> adapterService.saveCandidateResponse(
                    applicationId,
                    adapterService.requiredResponseType(stringValue(task, "responseType")),
                    stringValue(task, "responseMessage")
            );
            case "MarkCandidateResponseReceived" -> adapterService.markCandidateResponseReceived(applicationId);
            case "RecordCandidateResponseHistory" -> adapterService.recordCandidateResponseHistory(applicationId);
            case "PersistCandidateResponseToDb" -> adapterService.persistCandidateResponse(
                    applicationId,
                    ResponseType.valueOf(stringValue(task, "responseType")),
                    stringValue(task, "responseMessage")
            );
            case "NotifyCandidateResponse" -> adapterService.notifyCandidateResponse(applicationId);
            case "HandleVacancyClosedMessage" -> adapterService.handleVacancyClosedMessage(
                    applicationId,
                    stringValue(task, "closeReason")
            );
            default -> Map.of("adapterCompleted", true, "activityId", activityId);
        };
    }

    private Map<String, Object> handleTimeoutTask(String activityId, Map<String, Object> task) {
        if ("CloseByTimeout".equals(activityId)) {
            return adapterService.closeByTimeout(readRequiredUuid(task, "applicationId"));
        }
        switch (activityId) {
            case "FindOneExpiredInvitation" -> {
                return timeoutBatchProcessor.findOneExpiredInvitation();
            }
            case "CancelExpiredInvitationInterview" -> {
                return timeoutBatchProcessor.cancelExpiredInvitationInterview(readRequiredUuid(task, "expiredApplicationId"));
            }
            case "ReleaseExpiredInvitationSlot" -> {
                return timeoutBatchProcessor.releaseExpiredInvitationSlot(readRequiredUuid(task, "expiredApplicationId"));
            }
            case "CloseExpiredInvitationApplication" -> {
                return timeoutBatchProcessor.closeExpiredInvitationApplication(readRequiredUuid(task, "expiredApplicationId"));
            }
            case "RecordExpiredInvitationHistory" -> {
                return timeoutBatchProcessor.recordExpiredInvitationHistory(readRequiredUuid(task, "expiredApplicationId"));
            }
            case "NotifyExpiredInvitationParticipants" -> {
                return timeoutBatchProcessor.notifyExpiredInvitationParticipants(readRequiredUuid(task, "expiredApplicationId"));
            }
            case "CompleteExpiredInvitationProcess" -> {
                return timeoutBatchProcessor.completeExpiredInvitationProcess(readRequiredUuid(task, "expiredApplicationId"));
            }
            case "ProcessOneExpiredInvitation" -> {
                int batchClosed = timeoutBatchProcessor.processOneExpired();
                return Map.of(
                        "batchClosed", batchClosed,
                        "expiredFound", batchClosed > 0,
                        "timeoutBatchIterationCompleted", true);
            }
        }
        int closedCount = timeoutService.runCloseExpired();
        return Map.of("closedCount", closedCount, "timeoutScanCompleted", true);
    }

    private Map<String, Object> handleAdminInterviewResetTask(String activityId, Map<String, Object> task) {
        UUID interviewId = readRequiredUuid(task, "interviewId");
        UUID adminUserId = readRequiredUuid(task, "adminUserId");
        String resetReason = stringValue(task, "resetReason");
        return switch (activityId) {
            case "ValidateAdminResetForm" -> adapterService.validateAdminResetForm(interviewId, adminUserId, resetReason);
            case "ValidateInterviewCanBeReset" -> adapterService.validateInterviewCanBeReset(interviewId, adminUserId);
            case "CancelInterviewByAdmin" -> adapterService.cancelInterviewByAdmin(interviewId, resetReason);
            case "ReleaseAdminResetSlot" -> adapterService.releaseAdminResetSlot(interviewId);
            case "ReturnApplicationToReview" -> adapterService.returnApplicationToReview(interviewId, adminUserId, resetReason);
            case "RecordAdminResetHistory" -> adapterService.recordAdminResetHistory(interviewId, adminUserId);
            case "ResetInterviewToDb" -> adapterService.resetInterviewByAdminInDb(interviewId, adminUserId, resetReason);
            case "NotifyAdminResetParticipants" -> adapterService.notifyAdminInterviewReset(
                    readRequiredUuid(task, "applicationId"), resetReason);
            default -> adapterService.resetInterviewByAdmin(interviewId, adminUserId, resetReason);
        };
    }

    private Map<String, Object> handleVacancyCreateTask(String activityId, Map<String, Object> task) {
        return switch (activityId) {
            case "ValidateCreateVacancyForm" -> adapterService.validateCreateVacancyForm(
                    stringValue(task, "starterUserId"),
                    readUuid(task, "recruiterUserId"),
                    stringValue(task, "title"),
                    stringValue(task, "description"),
                    readValue(task, "requiredSkills"),
                    readValue(task, "screeningThreshold")
            );
            case "CreateVacancyFromForm" -> adapterService.createVacancyFromCamundaForm(
                    stringValue(task, "starterUserId"),
                    readUuid(task, "recruiterUserId"),
                    stringValue(task, "title"),
                    stringValue(task, "description"),
                    readValue(task, "requiredSkills"),
                    readValue(task, "screeningThreshold"),
                    task.get("processInstanceId") == null ? "" : String.valueOf(task.get("processInstanceId"))
            );
            default -> Map.of("vacancyCreateIgnored", true, "activityId", activityId);
        };
    }

    private Map<String, Object> handleVacancyCloseTask(String activityId, Map<String, Object> task) {
        UUID vacancyId = readRequiredUuid(task, "vacancyId");
        String closeReason = stringValue(task, "closeReason");
        return switch (activityId) {
            case "ValidateCloseVacancyForm" -> adapterService.validateCloseVacancyForm(vacancyId, stringValue(task, "action"), closeReason);
            case "MarkVacancyClosed" -> adapterService.markVacancyClosed(vacancyId);
            case "CancelActiveInterviewsForVacancy" -> adapterService.cancelActiveInterviewsForVacancy(vacancyId, closeReason);
            case "ReleaseScheduleSlotsForClosedVacancy" -> adapterService.releaseScheduleSlotsForClosedVacancy(vacancyId);
            case "CloseActiveApplicationsForVacancy" -> adapterService.closeActiveApplicationsForVacancy(vacancyId, closeReason);
            case "RecordVacancyClosedHistory" -> adapterService.recordVacancyClosedHistory(vacancyId);
            case "CloseVacancyAndApplicationsToDb" -> adapterService.closeVacancyApplicationsInDb(vacancyId, closeReason);
            case "NotifyVacancyClosedCandidates" -> adapterService.notifyVacancyClosedCandidates(vacancyId);
            case "CorrelateVacancyClosedApplications" -> adapterService.correlateVacancyClosedApplications(vacancyId, closeReason);
            default -> adapterService.closeVacancyApplications(vacancyId, closeReason);
        };
    }

    private Map<String, Object> handleVacancyStatusUpdateTask(String activityId, Map<String, Object> task) {
        UUID vacancyId = readRequiredUuid(task, "vacancyId");
        UUID recruiterUserId = readUuid(task, "recruiterUserId");
        String requestedStatus = stringValue(task, "requestedStatus");
        return switch (activityId) {
            case "ValidateVacancyStatusUpdate" -> adapterService.validateVacancyStatusUpdate(vacancyId, recruiterUserId, stringValue(task, "starterUserId"), requestedStatus);
            case "ApplyVacancyStatusUpdate" -> adapterService.applyVacancyStatusUpdate(vacancyId, recruiterUserId, stringValue(task, "starterUserId"), requestedStatus);
            default -> Map.of("vacancyStatusUpdateIgnored", true, "activityId", activityId);
        };
    }

    private Map<String, Object> handleInterviewCancelTask(String activityId, Map<String, Object> task) {
        UUID interviewId = readRequiredUuid(task, "interviewId");
        UUID recruiterUserId = readUuid(task, "recruiterUserId");
        String cancelReason = stringValue(task, "cancelReason");
        return switch (activityId) {
            case "ValidateRecruiterCancelInterview" -> adapterService.validateRecruiterCancelInterview(interviewId, recruiterUserId, stringValue(task, "starterUserId"), cancelReason);
            case "CancelInterviewByRecruiter" -> adapterService.cancelInterviewByRecruiter(interviewId, recruiterUserId, stringValue(task, "starterUserId"), cancelReason);
            case "ReleaseRecruiterCancelSlot" -> adapterService.releaseRecruiterCancelSlot(interviewId);
            case "ReturnCancelApplicationToReview" -> adapterService.returnCancelApplicationToReview(interviewId, recruiterUserId, stringValue(task, "starterUserId"), cancelReason);
            case "RecordRecruiterCancelHistory" -> adapterService.recordRecruiterCancelHistory(interviewId, recruiterUserId, stringValue(task, "starterUserId"));
            case "NotifyRecruiterCancelParticipants" -> adapterService.notifyRecruiterCancelParticipants(
                    readRequiredUuid(task, "applicationId"), cancelReason);
            default -> Map.of("interviewCancelIgnored", true, "activityId", activityId);
        };
    }

    private Map<String, Object> handleUiQueryTask(String activityId, Map<String, Object> task) {
        return switch (activityId) {
            case "LoadCandidateVacancyList" -> adapterService.loadCandidateVacancyList(stringValue(task, "starterUserId"));
            case "LoadCandidateApplicationList" -> adapterService.loadCandidateApplicationList(stringValue(task, "starterUserId"));
            case "LoadCandidateApplicationView" -> adapterService.loadCandidateApplicationView(
                    stringValue(task, "starterUserId"), stringValue(task, "applicationIdText"));
            case "LoadRecruiterVacancyList" -> adapterService.loadRecruiterVacancyList(stringValue(task, "starterUserId"));
            case "LoadRecruiterApplicationList" -> adapterService.loadRecruiterApplicationList(stringValue(task, "starterUserId"));
            case "LoadRecruiterApplicationView" -> adapterService.loadRecruiterApplicationView(
                    stringValue(task, "starterUserId"), stringValue(task, "applicationIdText"));
            case "LoadRecruiterSchedule" -> adapterService.loadRecruiterSchedule(
                    stringValue(task, "starterUserId"), readValue(task, "weekOffset"));
            case "LoadNotificationList" -> adapterService.loadNotificationList(stringValue(task, "starterUserId"));
            case "RunTimeoutReview" -> adapterService.runTimeoutReview(stringValue(task, "starterUserId"));
            default -> Map.of("uiQueryIgnored", true, "activityId", activityId);
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
            case "RollbackRecruiterCancel" -> adapterService.rollbackApplicationTransaction(
                    readRequiredUuid(task, "applicationId"), stringValue(task, "rollbackReason"));
            default -> Map.of("rollbackIgnored", true, "activityId", activityId);
        };
    }

    private boolean throwFormValidationBpmnError(String taskId, Map<String, Object> task, CamundaFormValidationException e) {
        Object applicationId = readValue(task, "applicationId");
        Map<String, Object> variables = new java.util.LinkedHashMap<>();
        if (applicationId != null) {
            variables.put("applicationId", applicationId);
        }
        variables.put("formErrorMessage", e.getMessage() == null ? "Invalid form data" : e.getMessage());
        variables.put("formErrorCode", FORM_VALIDATION_FAILED);
        return camundaRestClient.throwBpmnErrorExternalTask(
                taskId, FORM_VALIDATION_FAILED, e.getMessage(), variables);
    }

    private boolean throwRollbackBpmnError(String taskId, String activityId, Map<String, Object> task, Exception e) {
        String errorCode = switch (activityId) {
            case "CloseActiveApplications", "CloseVacancyAndApplicationsToDb", "NotifyVacancyClosedCandidates",
                 "MarkVacancyClosed", "CancelActiveInterviewsForVacancy", "ReleaseScheduleSlotsForClosedVacancy",
                 "CloseActiveApplicationsForVacancy", "RecordVacancyClosedHistory" -> VACANCY_TRANSACTION_FAILED;
            case "ResetInterviewTransaction", "ResetInterviewToDb", "NotifyAdminResetParticipants",
                 "ValidateInterviewCanBeReset", "CancelInterviewByAdmin", "ReleaseAdminResetSlot",
                 "ReturnApplicationToReview", "RecordAdminResetHistory" -> ADMIN_RESET_FAILED;
            case "CancelInterviewByRecruiter", "ReleaseRecruiterCancelSlot", "ReturnCancelApplicationToReview",
                 "RecordRecruiterCancelHistory", "NotifyRecruiterCancelParticipants" -> APPLICATION_TRANSACTION_FAILED;
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
        return required(value, name);
    }

    private UUID required(UUID value, String name) {
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
