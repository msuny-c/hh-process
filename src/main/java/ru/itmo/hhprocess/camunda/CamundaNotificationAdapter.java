package ru.itmo.hhprocess.camunda;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.enums.NotificationType;
import ru.itmo.hhprocess.repository.ApplicationRepository;
import ru.itmo.hhprocess.repository.VacancyRepository;
import ru.itmo.hhprocess.service.NotificationService;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CamundaNotificationAdapter {

    private final ApplicationRepository applicationRepository;
    private final VacancyRepository vacancyRepository;
    private final NotificationService notificationService;

    @Transactional
    public Map<String, Object> dispatchNotification(String notificationKind, UUID applicationId, UUID vacancyId,
                                                    String invitationMessage, String closeReason, String cancelReason,
                                                    String resetReason, String notificationTemplateCode) {
        NotificationDecision decision = NotificationDecision.parse(notificationKind, notificationTemplateCode);
        Map<String, Object> result = dispatchNotificationDecision(
                decision,
                applicationId,
                vacancyId,
                invitationMessage,
                closeReason,
                cancelReason,
                resetReason);
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

    @Transactional(readOnly = true)
    public Map<String, Object> prepareNotificationDecision(String notificationKind, UUID applicationId, UUID vacancyId,
                                                           String recipientRole) {
        String status = "NONE";
        if (applicationId != null) {
            status = applicationRepository.findDetailedById(applicationId)
                    .map(application -> application.getStatus().name())
                    .orElse("UNKNOWN");
        } else if (vacancyId != null) {
            status = vacancyRepository.findById(vacancyId)
                    .map(vacancy -> vacancy.getStatus().name())
                    .orElse("UNKNOWN");
        }
        return Map.of(
                "notificationKind", notificationKind == null || notificationKind.isBlank() ? "UNKNOWN" : notificationKind,
                "notificationStatus", status,
                "recipientRole", recipientRole == null || recipientRole.isBlank()
                        ? defaultNotificationRecipientRole(notificationKind)
                        : recipientRole
        );
    }

    @Transactional
    public Map<String, Object> notifyScreeningFailed(UUID applicationId) {
        ApplicationEntity application = getApplication(applicationId);
        notificationService.createIfAbsent(application.getCandidateUser(), application,
                NotificationType.SCREENING_RESULT, "Your application has been rejected");
        return Map.of("notificationSent", true, "status", application.getStatus().name());
    }

    @Transactional
    public Map<String, Object> notifyRecruiter(UUID applicationId) {
        ApplicationEntity application = getApplication(applicationId);
        notificationService.createIfAbsent(application.getVacancy().getRecruiterUser(), application,
                NotificationType.NEW_APPLICATION,
                "New application received for vacancy: " + application.getVacancy().getTitle());
        return Map.of("notificationSent", true, "status", application.getStatus().name());
    }

    @Transactional
    public Map<String, Object> notifyApplicationRejected(UUID applicationId) {
        ApplicationEntity application = getApplication(applicationId);
        notificationService.createIfAbsent(application.getCandidateUser(), application,
                NotificationType.APPLICATION_REJECTED, "Your application has been rejected");
        return Map.of("notificationSent", true, "status", application.getStatus().name());
    }

    @Transactional
    public Map<String, Object> notifyInvitation(UUID applicationId, String message) {
        ApplicationEntity application = getApplication(applicationId);
        notificationService.createIfAbsent(application.getCandidateUser(), application,
                NotificationType.INVITATION, "You have been invited to an interview: " + message);
        return Map.of("notificationSent", true, "status", application.getStatus().name());
    }

    @Transactional
    public Map<String, Object> notifyCandidateResponse(UUID applicationId) {
        ApplicationEntity application = getApplication(applicationId);
        notificationService.createIfAbsent(application.getVacancy().getRecruiterUser(), application,
                NotificationType.INVITATION_RESPONSE, "Candidate responded to interview invitation");
        return Map.of("notificationSent", true, "status", application.getStatus().name());
    }

    @Transactional
    public Map<String, Object> notifyAdminInterviewReset(UUID applicationId, String reason) {
        ApplicationEntity application = getApplication(applicationId);
        notificationService.create(application.getCandidateUser(), application,
                NotificationType.INTERVIEW_CANCELLED, "Interview was reset by administrator: " + reason);
        notificationService.create(application.getVacancy().getRecruiterUser(), application,
                NotificationType.INTERVIEW_CANCELLED, "Interview was reset by administrator: " + reason);
        return Map.of("adminResetNotificationSent", true, "applicationId", application.getId(),
                "status", application.getStatus().name());
    }

    @Transactional
    public Map<String, Object> notifyRecruiterCancelParticipants(UUID applicationId, String reason) {
        ApplicationEntity application = getApplication(applicationId);
        notificationService.create(application.getCandidateUser(), application,
                NotificationType.INTERVIEW_CANCELLED, "Interview was cancelled: " + reason);
        return Map.of("recruiterCancelNotificationSent", true, "applicationId", application.getId(),
                "status", application.getStatus().name());
    }

    @Transactional
    public Map<String, Object> notifyVacancyClosedCandidates(UUID vacancyId) {
        return notifyVacancyClosedCandidates(vacancyId, new NotificationDecision(
                "VACANCY_CLOSED",
                NotificationType.VACANCY_CLOSED,
                "CANDIDATE",
                "Vacancy was closed: {vacancyTitle}"));
    }

    private Map<String, Object> dispatchNotificationDecision(NotificationDecision decision, UUID applicationId, UUID vacancyId,
                                                             String invitationMessage, String closeReason, String cancelReason,
                                                             String resetReason) {
        if ("VACANCY_CLOSED".equals(decision.kind())) {
            return notifyVacancyClosedCandidates(requiredUuid(vacancyId, "vacancyId"), decision);
        }
        ApplicationEntity application = getApplication(requiredUuid(applicationId, "applicationId"));
        int sent = 0;
        if (decision.sendsToCandidate()) {
            notificationService.createIfAbsent(application.getCandidateUser(), application,
                    decision.type(), renderTemplate(decision.template(), application, invitationMessage, closeReason, cancelReason, resetReason));
            sent++;
        }
        if (decision.sendsToRecruiter()) {
            notificationService.createIfAbsent(application.getVacancy().getRecruiterUser(), application,
                    decision.type(), renderTemplate(decision.template(), application, invitationMessage, closeReason, cancelReason, resetReason));
            sent++;
        }
        return Map.of("notificationSent", true, "notifiedCount", sent, "status", application.getStatus().name());
    }

    private Map<String, Object> notifyVacancyClosedCandidates(UUID vacancyId, NotificationDecision decision) {
        var vacancy = vacancyRepository.findByIdForUpdate(vacancyId)
                .orElseThrow(() -> new IllegalArgumentException("Vacancy not found: " + vacancyId));
        List<ApplicationEntity> applications = applicationRepository.findByVacancyIdAndStatusIn(
                vacancyId, List.of(ApplicationStatus.CLOSED_BY_VACANCY));
        int notifiedCount = 0;
        for (ApplicationEntity application : applications) {
            notificationService.createIfAbsent(application.getCandidateUser(), application,
                    decision.type(), renderTemplate(decision.template(), application, "", "", "", ""));
            notifiedCount++;
        }
        return Map.of("notificationSent", true, "notifiedCount", notifiedCount, "status", vacancy.getStatus().name());
    }

    private String defaultNotificationRecipientRole(String notificationKind) {
        return switch (notificationKind == null ? "" : notificationKind) {
            case "NEW_APPLICATION", "CANDIDATE_RESPONSE" -> "RECRUITER";
            case "TIMEOUT", "ADMIN_RESET" -> "CANDIDATE,RECRUITER";
            default -> "CANDIDATE";
        };
    }

    private UUID requiredUuid(UUID value, String name) {
        if (value == null) {
            throw new IllegalArgumentException("Camunda notification variable is required: " + name);
        }
        return value;
    }

    private String renderTemplate(String template, ApplicationEntity application, String invitationMessage, String closeReason,
                                  String cancelReason, String resetReason) {
        String result = template == null || template.isBlank() ? "Notification for vacancy: {vacancyTitle}" : template;
        return result
                .replace("{vacancyTitle}", application.getVacancy().getTitle())
                .replace("{applicationStatus}", application.getStatus().name())
                .replace("{invitationMessage}", safeTemplateValue(invitationMessage))
                .replace("{closeReason}", safeTemplateValue(closeReason))
                .replace("{cancelReason}", safeTemplateValue(cancelReason))
                .replace("{resetReason}", safeTemplateValue(resetReason));
    }

    private String safeTemplateValue(String value) {
        return value == null ? "" : value;
    }

    private ApplicationEntity getApplication(UUID applicationId) {
        return applicationRepository.findDetailedById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Application not found: " + applicationId));
    }

    private record NotificationDecision(String kind, NotificationType type, String recipientRole, String template) {
        static NotificationDecision parse(String fallbackKind, String code) {
            if (code == null || code.isBlank()) {
                return defaultFor(fallbackKind);
            }
            String[] parts = code.split("\\|", 4);
            if (parts.length != 4) {
                return defaultFor(fallbackKind);
            }
            return new NotificationDecision(
                    parts[0],
                    NotificationType.valueOf(parts[1]),
                    parts[2],
                    parts[3]
            );
        }

        static NotificationDecision defaultFor(String kind) {
            return switch (kind) {
                case "SCREENING_FAILED" -> new NotificationDecision(kind, NotificationType.SCREENING_RESULT,
                        "CANDIDATE", "Your application has been rejected");
                case "NEW_APPLICATION" -> new NotificationDecision(kind, NotificationType.NEW_APPLICATION,
                        "RECRUITER", "New application received for vacancy: {vacancyTitle}");
                case "REJECTION" -> new NotificationDecision(kind, NotificationType.APPLICATION_REJECTED,
                        "CANDIDATE", "Your application has been rejected");
                case "INVITATION" -> new NotificationDecision(kind, NotificationType.INVITATION,
                        "CANDIDATE", "You have been invited to an interview: {invitationMessage}");
                case "CANDIDATE_RESPONSE" -> new NotificationDecision(kind, NotificationType.INVITATION_RESPONSE,
                        "RECRUITER", "Candidate responded to interview invitation");
                case "TIMEOUT" -> new NotificationDecision(kind, NotificationType.INVITATION_TIMEOUT,
                        "CANDIDATE,RECRUITER", "Interview invitation expired for vacancy: {vacancyTitle}");
                case "ADMIN_RESET" -> new NotificationDecision(kind, NotificationType.INTERVIEW_CANCELLED,
                        "CANDIDATE,RECRUITER", "Interview was reset by administrator: {resetReason}");
                case "RECRUITER_CANCEL" -> new NotificationDecision(kind, NotificationType.INTERVIEW_CANCELLED,
                        "CANDIDATE", "Interview was cancelled: {cancelReason}");
                case "VACANCY_CLOSED" -> new NotificationDecision(kind, NotificationType.VACANCY_CLOSED,
                        "CANDIDATE", "Vacancy was closed: {vacancyTitle}");
                default -> throw new IllegalArgumentException("Unsupported notification kind: " + kind);
            };
        }

        boolean sendsToCandidate() {
            return recipientRole.contains("CANDIDATE");
        }

        boolean sendsToRecruiter() {
            return recipientRole.contains("RECRUITER");
        }
    }
}
