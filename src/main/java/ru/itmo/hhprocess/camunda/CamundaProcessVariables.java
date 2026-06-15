package ru.itmo.hhprocess.camunda;

import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.entity.VacancyEntity;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.service.ApplicationService;
import ru.itmo.hhprocess.service.NotificationService;
import ru.itmo.hhprocess.service.ScreeningService.ScreeningInput;

import java.util.LinkedHashMap;
import java.util.Map;

public final class CamundaProcessVariables {

    private CamundaProcessVariables() {
    }

    public static Map<String, Object> formValidated() {
        return Map.of("formValidated", true, "formErrorMessage", "");
    }

    public static Map<String, Object> applyToVacancyValidated(ApplicationService.ApplyToVacancyValidationContext context) {
        Map<String, Object> variables = new LinkedHashMap<>(formValidated());
        if (context.applicationId() != null) {
            variables.put("applicationId", context.applicationId());
            return variables;
        }
        variables.put("vacancyId", context.vacancyId());
        variables.put("candidateUserId", context.candidateUserId());
        variables.put("candidateCamundaUserId", context.candidateCamundaUserId());
        variables.put("recruiterUserId", context.recruiterUserId());
        variables.put("vacancyTitle", context.vacancyTitle());
        return variables;
    }

    public static Map<String, Object> rejectionAllowed(String oldApplicationStatus) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("rejectionAllowed", true);
        variables.put("oldApplicationStatus", oldApplicationStatus);
        return variables;
    }

    public static Map<String, Object> prepareAutoScreenVariables(ScreeningInput input) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("screeningRequiredSkills", String.join(", ", input.requiredSkills()));
        variables.put("screeningMatchedSkills", String.join(", ", input.matchedSkills()));
        variables.put("screeningMatchedCount", input.matchedSkills().size());
        variables.put("screeningTotalSkills", input.requiredSkills().size());
        variables.put("screeningThreshold", input.threshold());
        variables.put("screeningScore", input.score());
        variables.put("screeningScoreDelta", input.score() - input.threshold());
        variables.put("autoScreeningPrepared", true);
        return variables;
    }

    public static Map<String, Object> saveAutoScreenDecisionVariables(ApplicationEntity application, boolean screeningPassed,
                                                                      int screeningScore) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("screeningPassed", screeningPassed);
        variables.put("status", application.getStatus().name());
        variables.put("screeningScore", screeningScore);
        variables.put("autoScreeningCompleted", true);
        variables.put("autoScreeningDecisionOwner", "Camunda DMN hhAutoScreening");
        return variables;
    }

    public static Map<String, Object> applicationStartVariables(VacancyEntity vacancy, UserEntity candidate,
                                                                ApplicationEntity application, String businessKey) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("applicationCreated", true);
        variables.put("applicationId", application.getId());
        variables.put("vacancyId", vacancy.getId());
        variables.put("candidateUserId", candidate.getId());
        variables.put("candidateCamundaUserId", CamundaIdentitySyncService.camundaUserId(candidate));
        variables.put("recruiterUserId", vacancy.getRecruiterUser().getId());
        variables.put("vacancyTitle", vacancy.getTitle());
        variables.put("status", application.getStatus().name());
        variables.put("applicationBusinessKey", businessKey);
        return variables;
    }

    public static Map<String, Object> applicationIdempotentVariables(ApplicationEntity application) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("applicationCreated", true);
        variables.put("applicationId", application.getId());
        variables.put("status", application.getStatus().name());
        variables.put("idempotent", true);
        return variables;
    }

    public static Map<String, Object> rejectionPersisted(ApplicationEntity application, boolean idempotent, boolean terminal) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("rejectionPersisted", !terminal);
        variables.put("status", application.getStatus().name());
        if (idempotent) {
            variables.put("idempotent", true);
        }
        if (terminal) {
            variables.put("terminal", true);
        }
        return variables;
    }

    public static Map<String, Object> notificationSent(ApplicationEntity application) {
        return Map.of("notificationSent", true, "status", application.getStatus().name());
    }

    public static Map<String, Object> rejectionInterviewCancelled() {
        return Map.of("rejectionInterviewCancelled", true);
    }

    public static Map<String, Object> applicationRejected(ApplicationEntity application, String oldStatus, boolean idempotent) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("applicationRejected", true);
        variables.put("status", application.getStatus().name());
        if (oldStatus != null) {
            variables.put("oldApplicationStatus", oldStatus);
        }
        if (idempotent) {
            variables.put("idempotent", true);
        }
        return variables;
    }

    public static Map<String, Object> rejectionHistoryRecorded(ApplicationEntity application) {
        return Map.of("rejectionHistoryRecorded", true, "status", application.getStatus().name());
    }

    public static Map<String, Object> timeoutClosed(ApplicationEntity application, boolean closed, boolean idempotent) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("timeoutClosed", closed);
        variables.put("status", application.getStatus().name());
        if (idempotent) {
            variables.put("idempotent", true);
        }
        return variables;
    }

    public static Map<String, Object> rollbackCompleted(ApplicationEntity application, ApplicationStatus oldStatus) {
        return Map.of(
                "rollbackCompleted", true,
                "applicationId", application.getId(),
                "oldStatus", oldStatus.name(),
                "status", application.getStatus().name()
        );
    }

    public static Map<String, Object> dispatchNotificationVariables(Map<String, Object> result, String notificationKind,
                                                                    NotificationService.NotificationDecision decision,
                                                                    String closeReason) {
        Map<String, Object> variables = new LinkedHashMap<>(result);
        variables.put("notificationDispatched", true);
        variables.put("notificationKind", notificationKind);
        variables.put("notificationType", decision.type().name());
        variables.put("recipientRole", decision.recipientRole());
        variables.put("notificationTemplate", decision.template());
        if (closeReason != null && !closeReason.isBlank()) {
            variables.put("closeReason", closeReason);
        }
        return variables;
    }
}
