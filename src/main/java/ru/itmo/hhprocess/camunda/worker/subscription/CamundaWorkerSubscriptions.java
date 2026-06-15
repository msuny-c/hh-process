package ru.itmo.hhprocess.camunda.worker.subscription;

import org.camunda.bpm.client.spring.annotation.ExternalTaskSubscription;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

public final class CamundaWorkerSubscriptions {

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @ExternalTaskSubscription(topicName = "application-auto-screening", variableNames = {
            "applicationId", "screeningPassed", "screeningScore"
    })
    public @interface AutoScreen {
    }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @ExternalTaskSubscription(topicName = "notification-send", variableNames = {
            "applicationId", "vacancyId", "recruiterComment", "invitationMessage",
            "scheduledAt", "durationMinutes", "responseType", "responseMessage", "closeReason", "candidateUserId",
            "starterUserId", "resumeText", "coverLetter", "interviewId"
    })
    public @interface NotificationSend {
    }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @ExternalTaskSubscription(topicName = "application-persistence", variableNames = {
            "applicationId", "vacancyId", "recruiterComment", "invitationMessage",
            "scheduledAt", "durationMinutes", "responseType", "responseMessage", "closeReason", "candidateUserId",
            "starterUserId", "resumeText", "coverLetter", "interviewId"
    })
    public @interface ApplicationPersistence {
    }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @ExternalTaskSubscription(topicName = "application-notification", variableNames = {
            "applicationId", "vacancyId", "recruiterComment", "invitationMessage",
            "scheduledAt", "durationMinutes", "responseType", "responseMessage", "closeReason", "candidateUserId",
            "starterUserId", "resumeText", "coverLetter", "interviewId"
    })
    public @interface ApplicationNotification {
    }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @ExternalTaskSubscription(topicName = "application-message", variableNames = {
            "applicationId", "vacancyId", "recruiterComment", "invitationMessage",
            "scheduledAt", "durationMinutes", "responseType", "responseMessage", "closeReason", "candidateUserId",
            "starterUserId", "resumeText", "coverLetter", "interviewId"
    })
    public @interface ApplicationMessage {
    }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @ExternalTaskSubscription(topicName = "form-validation", variableNames = {
            "applicationId", "vacancyId", "candidateUserId", "starterUserId",
            "resumeText", "coverLetter", "decision", "recruiterComment", "invitationMessage", "scheduledAt",
            "durationMinutes", "responseType", "responseMessage"
    })
    public @interface FormValidation {
    }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @ExternalTaskSubscription(topicName = "timeout-close-expired", variableNames = {
            "applicationId", "expiredApplicationId"
    })
    public @interface Timeout {
    }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @ExternalTaskSubscription(topicName = "vacancy-create", variableNames = {
            "starterUserId", "recruiterUserId", "title", "description",
            "requiredSkills", "screeningThreshold"
    })
    public @interface VacancyCreate {
    }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @ExternalTaskSubscription(topicName = "vacancy-close-applications", variableNames = {
            "vacancyId", "closeReason", "action"
    })
    public @interface VacancyClose {
    }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @ExternalTaskSubscription(topicName = "vacancy-status-update", variableNames = {
            "vacancyId", "recruiterUserId", "starterUserId", "requestedStatus"
    })
    public @interface VacancyStatusUpdate {
    }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @ExternalTaskSubscription(topicName = "interview-cancel", variableNames = {
            "interviewId", "recruiterUserId", "starterUserId", "cancelReason", "applicationId"
    })
    public @interface InterviewCancel {
    }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @ExternalTaskSubscription(topicName = "transaction-rollback", variableNames = {
            "applicationId", "vacancyId", "rollbackReason"
    })
    public @interface Rollback {
    }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @ExternalTaskSubscription(topicName = "admin-interview-reset", variableNames = {
            "interviewId", "adminUserId", "resetReason", "applicationId"
    })
    public @interface AdminInterviewReset {
    }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @ExternalTaskSubscription(topicName = "ui-query", variableNames = {
            "starterUserId", "applicationIdText", "weekOffset"
    })
    public @interface UiQuery {
    }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @ExternalTaskSubscription(topicName = "permission-check", variableNames = {
            "starterUserId", "recruiterUserId", "applicationId", "adminUserId"
    })
    public @interface PermissionCheck {
    }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @ExternalTaskSubscription(topicName = "status-transition", variableNames = {
            "applicationId", "vacancyId", "decision", "responseType", "requestedStatus"
    })
    public @interface StatusTransition {
    }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @ExternalTaskSubscription(topicName = "notification-decision", variableNames = {
            "notificationKind", "applicationId", "vacancyId", "recipientRole"
    })
    public @interface NotificationDecision {
    }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @ExternalTaskSubscription(topicName = "notification-dispatch", variableNames = {
            "notificationKind", "applicationId", "expiredApplicationId", "vacancyId", "invitationMessage",
            "closeReason", "cancelReason", "resetReason", "notificationTemplateCode"
    })
    public @interface NotificationDispatch {
    }

    private CamundaWorkerSubscriptions() {
    }
}
