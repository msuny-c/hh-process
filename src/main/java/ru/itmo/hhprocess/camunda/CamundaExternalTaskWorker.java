package ru.itmo.hhprocess.camunda;

import ru.itmo.hhprocess.config.CamundaProperties;

import ru.itmo.hhprocess.exception.CamundaFormValidationException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.client.ExternalTaskClient;
import org.camunda.bpm.client.task.ExternalTask;
import org.camunda.bpm.client.task.ExternalTaskService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.enums.ResponseType;
import ru.itmo.hhprocess.exception.ApiException;
import ru.itmo.hhprocess.service.TimeoutBatchProcessor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

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
    private static final String TOPIC_ADMIN_USER_PROVISION = "admin-user-provision";
    private static final String TOPIC_UI_QUERY = "ui-query";
    private static final String TOPIC_PERMISSION_CHECK = "permission-check";
    private static final String TOPIC_STATUS_TRANSITION = "status-transition";
    private static final String TOPIC_NOTIFICATION_DECISION = "notification-decision";
    private static final String TOPIC_NOTIFICATION_DISPATCH = "notification-dispatch";
    private static final String APPLICATION_TRANSACTION_FAILED = "APPLICATION_TX_FAILED";
    private static final String VACANCY_TRANSACTION_FAILED = "VACANCY_TX_FAILED";
    private static final String FORM_VALIDATION_FAILED = "FORM_VALIDATION_FAILED";

    private static final List<String> VARIABLES = List.of(
            "applicationId", "expiredApplicationId", "interviewId", "scheduleSlotId",
            "oldApplicationStatus", "formErrorMessage", "formErrorField", "formErrorFields", "formErrorCode",
            "vacancyId", "candidateUserId", "candidateCamundaUserId", "recruiterUserId", "recruiterCamundaUserId",
            "adminUserId", "vacancyTitle", "starterUserId", "title", "description", "requiredSkills",
            "screeningThreshold", "screeningScore", "screeningScoreDelta", "screeningMatchedCount",
            "screeningTotalSkills", "requestedStatus", "resumeText", "coverLetter", "screeningPassed", "status",
            "action", "decision", "recruiterComment", "invitationMessage", "scheduledAt", "durationMinutes",
            "responseType", "responseMessage", "closeReason", "cancelReason", "resetReason", "rollbackReason",
            "permissionRole", "permissionOperation", "permissionOwnership", "permissionAllowed", "permissionChecked",
            "currentStatus", "statusAction", "statusTransition", "notificationStatus", "recipientRole",
            "notificationTemplateCode", "notificationTemplate", "notificationType", "notificationKind",
            "notificationDispatched", "applicationIdText", "weekOffset", "uiTitle", "uiPayload",
            "email", "password", "firstName", "lastName");

    private final ExternalTaskClient externalTaskClient;
    private final TimeoutBatchProcessor timeoutBatchProcessor;
    private final CamundaProcessAdapterService adapterService;
    private final CamundaProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    void subscribe() {
        if (!properties.isEnabled()) return;
        register(TOPIC_AUTO_SCREEN, this::handleAutoScreenTask);
        register(TOPIC_NOTIFY, this::handleNotificationBackedTask);
        register(TOPIC_APPLICATION_PERSISTENCE, this::handleNotificationBackedTask);
        register(TOPIC_APPLICATION_NOTIFICATION, this::handleNotificationBackedTask);
        register(TOPIC_APPLICATION_MESSAGE, this::handleNotificationBackedTask);
        register(TOPIC_FORM_VALIDATION, this::handleFormValidationTask);
        register(TOPIC_TIMEOUT, this::handleTimeoutTask);
        register(TOPIC_VACANCY_CREATE, this::handleVacancyCreateTask);
        register(TOPIC_VACANCY_CLOSE, this::handleVacancyCloseTask);
        register(TOPIC_VACANCY_STATUS_UPDATE, this::handleVacancyStatusUpdateTask);
        register(TOPIC_INTERVIEW_CANCEL, this::handleInterviewCancelTask);
        register(TOPIC_ROLLBACK, this::handleRollbackTask);
        register(TOPIC_ADMIN_USER_PROVISION, this::handleAdminUserProvisionTask);
        register(TOPIC_UI_QUERY, this::handleUiQueryTask);
        register(TOPIC_PERMISSION_CHECK, this::handlePermissionTask);
        register(TOPIC_STATUS_TRANSITION, this::handleStatusTransitionTask);
        register(TOPIC_NOTIFICATION_DECISION, (id, t) -> handleNotificationDecisionTask(t));
        register(TOPIC_NOTIFICATION_DISPATCH, (id, t) -> handleNotificationDispatchTask(t));
    }

    private void register(String topic, BiFunction<String, ExternalTask, Map<String, Object>> handler) {
        externalTaskClient.subscribe(topic)
                .variables(VARIABLES.toArray(String[]::new))
                .lockDuration(properties.getWorker().getLockDurationMs())
                .handler((task, service) -> {
                    String activityId = task.getActivityId();
                    try {
                        service.complete(task, flattenForClient(handler.apply(activityId, task)));
                    } catch (CamundaFormValidationException e) {
                        log.warn("Camunda form validation failed; topic={}, activityId={}: {}",
                                topic, activityId, e.getMessage());
                        if (!throwFormValidationBpmnError(service, task, e)) {
                            service.handleFailure(task, e.getMessage(), stackTraceToString(e), 3, 10_000L);
                        }
                    } catch (Exception e) {
                        log.error("Camunda external task failed; topic={}, activityId={}", topic, activityId, e);
                        if (!shouldRouteToBpmnRollback(e) || !throwRollbackBpmnError(service, task, activityId, e)) {
                            service.handleFailure(task,
                                    e.getMessage() == null ? "External task failed" : e.getMessage(),
                                    stackTraceToString(e), 3, 10_000L);
                        }
                    }
                })
                .open();
    }

    private Map<String, Object> handleAutoScreenTask(String activityId, ExternalTask task) {
        UUID applicationId = requiredUuid(task, "applicationId");
        return switch (activityId) {
            case "AutoScreenApplication" -> adapterService.prepareAutoScreen(applicationId);
            case "SaveAutoScreenDecision" -> adapterService.saveAutoScreenDecision(
                    applicationId,
                    bool(task, "screeningPassed"),
                    integer(task, "screeningScore"));
            default -> adapterService.prepareAutoScreen(applicationId);
        };
    }

    private Map<String, Object> handleFormValidationTask(String activityId, ExternalTask task) {
        UUID applicationId = uuid(task, "applicationId");
        return switch (activityId) {
            case "ValidateApplyToVacancyForm" -> adapterService.validateApplyToVacancyForm(
                    applicationId,
                    uuid(task, "vacancyId"),
                    uuid(task, "candidateUserId"),
                    str(task, "starterUserId"),
                    str(task, "resumeText"),
                    str(task, "coverLetter")
            );
            case "ValidateRecruiterDecisionForm" -> adapterService.validateRecruiterDecisionForm(
                    requireNonNull(applicationId, "applicationId"),
                    str(task, "decision"),
                    str(task, "recruiterComment")
            );
            case "ValidateInvitationForm" -> adapterService.validateInvitationForm(
                    requireNonNull(applicationId, "applicationId"),
                    str(task, "invitationMessage"),
                    adapterService.requiredScheduledAt(task.getVariable("scheduledAt")),
                    adapterService.requiredDurationMinutes(task.getVariable("durationMinutes"))
            );
            case "ValidateCandidateResponseForm" -> adapterService.validateCandidateResponseForm(
                    requireNonNull(applicationId, "applicationId"),
                    str(task, "responseType"),
                    str(task, "responseMessage")
            );
            default -> Map.of("formValidationIgnored", true, "activityId", activityId);
        };
    }

    private Map<String, Object> handleNotificationBackedTask(String activityId, ExternalTask task) {
        UUID applicationId = uuid(task, "applicationId");
        return switch (activityId) {
            case "CreateApplicationFromForm" -> adapterService.createApplicationFromCamundaForm(
                    uuid(task, "applicationId"),
                    requiredUuid(task, "vacancyId"),
                    uuid(task, "candidateUserId"),
                    str(task, "starterUserId"),
                    str(task, "resumeText"),
                    str(task, "coverLetter"),
                    task.getProcessInstanceId() == null ? "" : task.getProcessInstanceId()
            );
            case "NotifyScreeningFailed" -> adapterService.notifyScreeningFailed(applicationId);
            case "NotifyRecruiter" -> adapterService.notifyRecruiter(applicationId);
            case "PersistRejection" -> {
                Map<String, Object> variables = new LinkedHashMap<>(
                        adapterService.rejectApplication(applicationId, str(task, "recruiterComment")));
                variables.putAll(adapterService.notifyApplicationRejected(applicationId));
                yield variables;
            }
            case "ValidateRejectionAllowed" -> adapterService.validateRejectionAllowed(applicationId, str(task, "recruiterComment"));
            case "CancelRejectionInterviewIfAny" -> adapterService.cancelRejectionInterviewIfAny(applicationId, str(task, "recruiterComment"));
            case "MarkApplicationRejected" -> adapterService.markApplicationRejected(applicationId, str(task, "recruiterComment"));
            case "RecordRejectionHistory" -> adapterService.recordRejectionHistory(applicationId);
            case "PersistRejectionToDb" -> adapterService.rejectApplication(applicationId, str(task, "recruiterComment"));
            case "NotifyRejection" -> adapterService.notifyApplicationRejected(applicationId);
            case "PersistInvitation" -> {
                String invitationMessage = str(task, "invitationMessage");
                Map<String, Object> variables = new LinkedHashMap<>(adapterService.persistInvitation(
                        applicationId,
                        invitationMessage,
                        adapterService.scheduledAtOrDefault(task.getVariable("scheduledAt")),
                        adapterService.durationOrDefault(task.getVariable("durationMinutes"))
                ));
                variables.putAll(adapterService.notifyInvitation(applicationId, invitationMessage));
                yield variables;
            }
            case "PersistInvitationToDb" -> adapterService.saveInvitationToDb(
                    applicationId, str(task, "invitationMessage"));
            case "CreateInvitationInterview" -> adapterService.createInvitationInterview(
                    applicationId,
                    str(task, "invitationMessage"),
                    adapterService.requiredScheduledAt(task.getVariable("scheduledAt")),
                    adapterService.requiredDurationMinutes(task.getVariable("durationMinutes"))
            );
            case "ReserveInvitationSlot" -> adapterService.reserveInvitationSlot(
                    applicationId,
                    requiredUuid(task, "interviewId"),
                    adapterService.requiredScheduledAt(task.getVariable("scheduledAt")),
                    adapterService.requiredDurationMinutes(task.getVariable("durationMinutes"))
            );
            case "RecordInvitationHistory" -> adapterService.recordInvitationHistory(applicationId);
            case "NotifyInvitation" -> adapterService.notifyInvitation(applicationId, str(task, "invitationMessage"));
            case "PersistCandidateResponse" -> {
                Map<String, Object> variables = new LinkedHashMap<>(adapterService.persistCandidateResponse(
                        applicationId,
                        ResponseType.valueOf(str(task, "responseType")),
                        str(task, "responseMessage")
                ));
                variables.putAll(adapterService.notifyCandidateResponse(applicationId));
                yield variables;
            }
            case "CheckInvitationStillActive" -> adapterService.checkInvitationStillActive(applicationId);
            case "SaveCandidateResponse" -> adapterService.saveCandidateResponse(
                    applicationId,
                    adapterService.requiredResponseType(str(task, "responseType")),
                    str(task, "responseMessage")
            );
            case "MarkCandidateResponseReceived" -> adapterService.markCandidateResponseReceived(applicationId);
            case "RecordCandidateResponseHistory" -> adapterService.recordCandidateResponseHistory(applicationId);
            case "PersistCandidateResponseToDb" -> adapterService.persistCandidateResponse(
                    applicationId,
                    ResponseType.valueOf(str(task, "responseType")),
                    str(task, "responseMessage")
            );
            case "NotifyCandidateResponse" -> adapterService.notifyCandidateResponse(applicationId);
            case "HandleVacancyClosedMessage" -> adapterService.handleVacancyClosedMessage(
                    applicationId, str(task, "closeReason"));
            default -> Map.of("adapterCompleted", true, "activityId", activityId);
        };
    }

    private Map<String, Object> handleTimeoutTask(String activityId, ExternalTask task) {
        if ("CloseByTimeout".equals(activityId)) {
            return adapterService.closeByTimeout(requiredUuid(task, "applicationId"));
        }
        switch (activityId) {
            case "FindOneExpiredInvitation" -> { return timeoutBatchProcessor.findOneExpiredInvitation(); }
            case "CancelExpiredInvitationInterview" -> { return timeoutBatchProcessor.cancelExpiredInvitationInterview(requiredUuid(task, "expiredApplicationId")); }
            case "ReleaseExpiredInvitationSlot" -> { return timeoutBatchProcessor.releaseExpiredInvitationSlot(requiredUuid(task, "expiredApplicationId")); }
            case "CloseExpiredInvitationApplication" -> { return timeoutBatchProcessor.closeExpiredInvitationApplication(requiredUuid(task, "expiredApplicationId")); }
            case "RecordExpiredInvitationHistory" -> { return timeoutBatchProcessor.recordExpiredInvitationHistory(requiredUuid(task, "expiredApplicationId")); }
            case "NotifyExpiredInvitationParticipants" -> { return timeoutBatchProcessor.notifyExpiredInvitationParticipants(requiredUuid(task, "expiredApplicationId")); }
            case "CompleteExpiredInvitationProcess" -> { return timeoutBatchProcessor.completeExpiredInvitationProcess(requiredUuid(task, "expiredApplicationId")); }
            case "ProcessOneExpiredInvitation" -> {
                int batchClosed = timeoutBatchProcessor.processOneExpired();
                return Map.of("batchClosed", batchClosed, "expiredFound", batchClosed > 0, "timeoutBatchIterationCompleted", true);
            }
        }
        return Map.of("timeoutTaskIgnored", true, "activityId", activityId);
    }

    private Map<String, Object> handleAdminUserProvisionTask(String activityId, ExternalTask task) {
        String role = switch (activityId) {
            case "ProvisionCandidateUser" -> "CANDIDATE";
            case "ProvisionRecruiterUser" -> "RECRUITER";
            default -> "";
        };
        return adapterService.provisionUserFromAdminForm(
                str(task, "starterUserId"), role,
                str(task, "email"), str(task, "password"),
                str(task, "firstName"), str(task, "lastName")
        );
    }

    private Map<String, Object> handleVacancyCreateTask(String activityId, ExternalTask task) {
        return switch (activityId) {
            case "ValidateCreateVacancyForm" -> adapterService.validateCreateVacancyForm(
                    str(task, "starterUserId"), uuid(task, "recruiterUserId"),
                    str(task, "title"), str(task, "description"),
                    task.getVariable("requiredSkills"), task.getVariable("screeningThreshold")
            );
            case "CreateVacancyFromForm" -> adapterService.createVacancyFromCamundaForm(
                    str(task, "starterUserId"), uuid(task, "recruiterUserId"),
                    str(task, "title"), str(task, "description"),
                    task.getVariable("requiredSkills"), task.getVariable("screeningThreshold"),
                    task.getProcessInstanceId() == null ? "" : task.getProcessInstanceId()
            );
            default -> Map.of("vacancyCreateIgnored", true, "activityId", activityId);
        };
    }

    private Map<String, Object> handleVacancyCloseTask(String activityId, ExternalTask task) {
        UUID vacancyId = requiredUuid(task, "vacancyId");
        String closeReason = str(task, "closeReason");
        return switch (activityId) {
            case "ValidateCloseVacancyForm" -> adapterService.validateCloseVacancyForm(vacancyId, str(task, "action"), closeReason);
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

    private Map<String, Object> handleVacancyStatusUpdateTask(String activityId, ExternalTask task) {
        UUID vacancyId = requiredUuid(task, "vacancyId");
        UUID recruiterUserId = uuid(task, "recruiterUserId");
        String requestedStatus = str(task, "requestedStatus");
        return switch (activityId) {
            case "ValidateVacancyStatusUpdate" -> adapterService.validateVacancyStatusUpdate(
                    vacancyId, recruiterUserId, str(task, "starterUserId"), requestedStatus);
            case "ApplyVacancyStatusUpdate" -> adapterService.applyVacancyStatusUpdate(
                    vacancyId, recruiterUserId, str(task, "starterUserId"), requestedStatus);
            default -> Map.of("vacancyStatusUpdateIgnored", true, "activityId", activityId);
        };
    }

    private Map<String, Object> handleInterviewCancelTask(String activityId, ExternalTask task) {
        UUID interviewId = requiredUuid(task, "interviewId");
        UUID recruiterUserId = uuid(task, "recruiterUserId");
        String cancelReason = str(task, "cancelReason");
        return switch (activityId) {
            case "ValidateRecruiterCancelInterview" -> adapterService.validateRecruiterCancelInterview(
                    interviewId, recruiterUserId, str(task, "starterUserId"), cancelReason);
            case "CancelInterviewByRecruiter" -> adapterService.cancelInterviewByRecruiter(
                    interviewId, recruiterUserId, str(task, "starterUserId"), cancelReason);
            case "ReleaseRecruiterCancelSlot" -> adapterService.releaseRecruiterCancelSlot(interviewId);
            case "ReturnCancelApplicationToReview" -> adapterService.returnCancelApplicationToReview(
                    interviewId, recruiterUserId, str(task, "starterUserId"), cancelReason);
            case "RecordRecruiterCancelHistory" -> adapterService.recordRecruiterCancelHistory(
                    interviewId, recruiterUserId, str(task, "starterUserId"));
            case "NotifyRecruiterCancelParticipants" -> adapterService.notifyRecruiterCancelParticipants(
                    requiredUuid(task, "applicationId"), cancelReason);
            default -> Map.of("interviewCancelIgnored", true, "activityId", activityId);
        };
    }

    private Map<String, Object> handleUiQueryTask(String activityId, ExternalTask task) {
        return switch (activityId) {
            case "LoadCandidateVacancyList" -> adapterService.loadCandidateVacancyList(str(task, "starterUserId"));
            case "LoadCandidateApplicationList" -> adapterService.loadCandidateApplicationList(str(task, "starterUserId"));
            case "LoadCandidateApplicationView" -> adapterService.loadCandidateApplicationView(
                    str(task, "starterUserId"), str(task, "applicationIdText"));
            case "LoadRecruiterVacancyList" -> adapterService.loadRecruiterVacancyList(str(task, "starterUserId"));
            case "LoadRecruiterApplicationList" -> adapterService.loadRecruiterApplicationList(str(task, "starterUserId"));
            case "LoadRecruiterApplicationView" -> adapterService.loadRecruiterApplicationView(
                    str(task, "starterUserId"), str(task, "applicationIdText"));
            case "LoadRecruiterSchedule" -> adapterService.loadRecruiterSchedule(
                    str(task, "starterUserId"), task.getVariable("weekOffset"));
            case "LoadNotificationList" -> adapterService.loadNotificationList(str(task, "starterUserId"));
            case "RunTimeoutReview" -> adapterService.runTimeoutReview(str(task, "starterUserId"));
            default -> Map.of("uiQueryIgnored", true, "activityId", activityId);
        };
    }

    private Map<String, Object> handlePermissionTask(String activityId, ExternalTask task) {
        return switch (activityId) {
            case "ResolveCreateVacancyPermission" -> adapterService.resolveCreateVacancyPermission(
                    str(task, "starterUserId"), uuid(task, "recruiterUserId"));
            case "ResolveRecruiterDecisionPermission" -> adapterService.resolveRecruiterDecisionPermission(
                    str(task, "starterUserId"), uuid(task, "applicationId"));
            case "ResolveCandidateResponsePermission" -> adapterService.resolveCandidateResponsePermission(
                    str(task, "starterUserId"), uuid(task, "applicationId"));
            default -> adapterService.resolveOperationPermission("SYSTEM", "UNKNOWN", false);
        };
    }

    private Map<String, Object> handleStatusTransitionTask(String activityId, ExternalTask task) {
        return switch (activityId) {
            case "PrepareRecruiterDecisionTransition" -> adapterService.prepareRecruiterDecisionTransition(
                    requiredUuid(task, "applicationId"), str(task, "decision"));
            case "PrepareCandidateResponseTransition" -> adapterService.prepareCandidateResponseTransition(
                    requiredUuid(task, "applicationId"), str(task, "responseType"));
            case "PrepareCloseVacancyTransition" -> adapterService.prepareCloseVacancyTransition(
                    requiredUuid(task, "vacancyId"));
            case "PrepareVacancyStatusTransition" -> adapterService.prepareVacancyStatusTransition(
                    requiredUuid(task, "vacancyId"), str(task, "requestedStatus"));
            default -> adapterService.prepareStatusTransition("UNKNOWN", "UNKNOWN", "");
        };
    }

    private Map<String, Object> handleNotificationDecisionTask(ExternalTask task) {
        return adapterService.prepareNotificationDecision(
                str(task, "notificationKind"),
                uuid(task, "applicationId"),
                uuid(task, "vacancyId"),
                str(task, "recipientRole")
        );
    }

    private Map<String, Object> handleNotificationDispatchTask(ExternalTask task) {
        UUID applicationId = uuid(task, "applicationId");
        if (applicationId == null) applicationId = uuid(task, "expiredApplicationId");
        return adapterService.dispatchNotification(
                str(task, "notificationKind"),
                applicationId,
                uuid(task, "vacancyId"),
                str(task, "invitationMessage"),
                str(task, "closeReason"),
                str(task, "cancelReason"),
                str(task, "resetReason"),
                str(task, "notificationTemplateCode")
        );
    }

    private Map<String, Object> handleRollbackTask(String activityId, ExternalTask task) {
        return switch (activityId) {
            case "RollbackApplicationTransaction", "RollbackRecruiterCancel" ->
                    adapterService.rollbackApplicationTransaction(
                            requiredUuid(task, "applicationId"), str(task, "rollbackReason"));
            case "RollbackVacancyTransaction" -> adapterService.rollbackVacancyTransaction(
                    requiredUuid(task, "vacancyId"), str(task, "rollbackReason"));
            default -> Map.of("rollbackIgnored", true, "activityId", activityId);
        };
    }

    private boolean throwFormValidationBpmnError(ExternalTaskService service, ExternalTask task, CamundaFormValidationException e) {
        String fieldName = e.getFieldName() == null ? "" : e.getFieldName();
        Map<String, Object> variables = new LinkedHashMap<>();
        Object applicationId = task.getVariable("applicationId");
        if (applicationId != null) variables.put("applicationId", String.valueOf(applicationId));
        variables.put("formErrorMessage", e.getMessage() == null ? "Invalid form data" : e.getMessage());
        variables.put("formErrorField", fieldName);
        variables.put("formErrorFields", fieldName);
        variables.put("formErrorCode", FORM_VALIDATION_FAILED);
        try {
            service.handleBpmnError(task, FORM_VALIDATION_FAILED, e.getMessage(), variables);
            return true;
        } catch (Exception ex) {
            log.warn("Cannot throw form validation BPMN error for task {}: {}", task.getId(), ex.getMessage());
            return false;
        }
    }

    private boolean throwRollbackBpmnError(ExternalTaskService service, ExternalTask task, String activityId, Exception e) {
        String errorCode = switch (activityId) {
            case "CloseActiveApplications", "CloseVacancyAndApplicationsToDb", "NotifyVacancyClosedCandidates",
                 "MarkVacancyClosed", "CancelActiveInterviewsForVacancy", "ReleaseScheduleSlotsForClosedVacancy",
                 "CloseActiveApplicationsForVacancy", "RecordVacancyClosedHistory" -> VACANCY_TRANSACTION_FAILED;
            default -> APPLICATION_TRANSACTION_FAILED;
        };
        Map<String, Object> variables = new LinkedHashMap<>();
        Object applicationId = task.getVariable("applicationId");
        Object vacancyId = task.getVariable("vacancyId");
        if (applicationId != null) variables.put("applicationId", String.valueOf(applicationId));
        if (vacancyId != null) variables.put("vacancyId", String.valueOf(vacancyId));
        variables.put("rollbackReason", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        try {
            service.handleBpmnError(task, errorCode, e.getMessage(), variables);
            return true;
        } catch (Exception ex) {
            log.warn("Cannot throw rollback BPMN error for task {}: {}", task.getId(), ex.getMessage());
            return false;
        }
    }

    private boolean shouldRouteToBpmnRollback(Exception e) {
        return e instanceof ApiException || e instanceof IllegalArgumentException;
    }

    private UUID requiredUuid(ExternalTask task, String name) {
        UUID value = uuid(task, name);
        if (value == null) throw new IllegalArgumentException("Camunda external task variable is required: " + name);
        return value;
    }

    private UUID uuid(ExternalTask task, String name) {
        Object value = task.getVariable(name);
        if (value == null || String.valueOf(value).isBlank()) return null;
        try {
            return UUID.fromString(String.valueOf(value).trim());
        } catch (IllegalArgumentException e) {
            throw new CamundaFormValidationException(name, "Некорректный ID: " + name);
        }
    }

    private <T> T requireNonNull(T value, String name) {
        if (value == null) throw new IllegalArgumentException("Camunda external task variable is required: " + name);
        return value;
    }

    private String str(ExternalTask task, String name) {
        Object value = task.getVariable(name);
        return value == null ? "" : String.valueOf(value);
    }

    private boolean bool(ExternalTask task, String name) {
        Object value = task.getVariable(name);
        if (value instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private int integer(ExternalTask task, String name) {
        Object value = task.getVariable(name);
        if (value instanceof Number n) return n.intValue();
        return Integer.parseInt(String.valueOf(value));
    }

    private static Map<String, Object> flattenForClient(Map<String, Object> vars) {
        if (vars == null || vars.isEmpty()) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>(vars.size());
        vars.forEach((k, v) ->
                result.put(k, (v == null || v instanceof Boolean || v instanceof Number) ? v : String.valueOf(v)));
        return result;
    }

    private static String stackTraceToString(Exception e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
