package ru.itmo.hhprocess.camunda.worker.subscription;

import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.client.task.ExternalTask;
import org.camunda.bpm.client.task.ExternalTaskHandler;
import org.camunda.bpm.client.task.ExternalTaskService;
import org.camunda.bpm.engine.variable.Variables;
import org.camunda.bpm.engine.variable.VariableMap;
import ru.itmo.hhprocess.exception.CamundaFormValidationException;
import ru.itmo.hhprocess.utils.CamundaFormValidator;
import ru.itmo.hhprocess.utils.CamundaTaskVariables;
import ru.itmo.hhprocess.exception.ApiException;

import java.util.Map;

@Slf4j
public abstract class AbstractExternalTaskWorker implements ExternalTaskHandler {

    public static final String APPLICATION_TRANSACTION_FAILED = "APPLICATION_TX_FAILED";
    public static final String VACANCY_TRANSACTION_FAILED = "VACANCY_TX_FAILED";
    public static final String ADMIN_RESET_FAILED = "ADMIN_RESET_FAILED";
    public static final String FORM_VALIDATION_FAILED = "FORM_VALIDATION_FAILED";

    protected final CamundaFormValidator formValidator;

    protected AbstractExternalTaskWorker(CamundaFormValidator formValidator) {
        this.formValidator = formValidator;
    }

    @Override
    public void execute(ExternalTask externalTask, ExternalTaskService externalTaskService) {
        String activityId = externalTask.getActivityId();
        try {
            Map<String, Object> variables = handle(activityId, new CamundaTaskVariables(externalTask, formValidator));
            complete(externalTask, externalTaskService, variables);
        } catch (CamundaFormValidationException exception) {
            if (handleFormValidationError(externalTask, externalTaskService, exception)) {
                return;
            }
            handleFailure(externalTask, externalTaskService, exception);
        } catch (Exception exception) {
            log.error("Camunda external task failed; taskId={}, activityId={}", externalTask.getId(), activityId,
                    exception);
            if (shouldRouteToBpmnRollback(exception)
                    && handleRollbackError(externalTask, externalTaskService, activityId, exception)) {
                return;
            }
            handleFailure(externalTask, externalTaskService, exception);
        }
    }

    protected abstract Map<String, Object> handle(String activityId, CamundaTaskVariables variables);

    public static boolean handleFormValidationError(ExternalTask task, ExternalTaskService service,
                                             CamundaFormValidationException exception) {
        log.warn("Camunda form validation failed; taskId={}, activityId={}, message={}",
                task.getId(), task.getActivityId(), exception.getMessage());
        CamundaTaskVariables variables = new CamundaTaskVariables(task);
        VariableMap errorVariables = Variables.createVariables();
        Object applicationId = variables.readValue("applicationId");
        if (applicationId != null) {
            errorVariables.put("applicationId", String.valueOf(applicationId));
        }
        String fieldName = exception.getFieldName() == null ? "" : exception.getFieldName();
        errorVariables.put("formErrorMessage",
                exception.getMessage() == null ? "Invalid form data" : exception.getMessage());
        errorVariables.put("formErrorField", fieldName);
        errorVariables.put("formErrorFields", fieldName);
        errorVariables.put("formErrorCode", FORM_VALIDATION_FAILED);
        service.handleBpmnError(task, FORM_VALIDATION_FAILED, exception.getMessage(), errorVariables);
        return true;
    }

    static boolean handleRollbackError(ExternalTask task, ExternalTaskService service, String activityId,
                                       Exception exception) {
        String errorCode = resolveRollbackErrorCode(activityId);
        CamundaTaskVariables variables = new CamundaTaskVariables(task);
        VariableMap errorVariables = Variables.createVariables();
        Object applicationId = variables.readValue("applicationId");
        Object vacancyId = variables.readValue("vacancyId");
        if (applicationId != null) {
            errorVariables.put("applicationId", String.valueOf(applicationId));
        }
        if (vacancyId != null) {
            errorVariables.put("vacancyId", String.valueOf(vacancyId));
        }
        errorVariables.put("rollbackReason",
                exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
        service.handleBpmnError(task, errorCode, exception.getMessage(), errorVariables);
        return true;
    }

    static boolean shouldRouteToBpmnRollback(Exception exception) {
        return exception instanceof ApiException || exception instanceof IllegalArgumentException;
    }

    static void handleFailure(ExternalTask task, ExternalTaskService service, Exception exception) {
        service.handleFailure(task, exception.getMessage(), stackTraceToString(exception), 0, 0L);
    }

    static String resolveRollbackErrorCode(String activityId) {
        return switch (activityId) {
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
    }

    private static void complete(ExternalTask externalTask, ExternalTaskService externalTaskService,
                                 Map<String, Object> variables) {
        if (variables == null || variables.isEmpty()) {
            externalTaskService.complete(externalTask);
            return;
        }
        VariableMap variableMap = Variables.createVariables();
        variables.forEach(variableMap::put);
        externalTaskService.complete(externalTask, variableMap);
    }

    private static String stackTraceToString(Exception exception) {
        java.io.StringWriter stringWriter = new java.io.StringWriter();
        exception.printStackTrace(new java.io.PrintWriter(stringWriter));
        return stringWriter.toString();
    }
}
