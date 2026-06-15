package ru.itmo.hhprocess.service;

import org.springframework.context.ApplicationEventPublisher;
import lombok.RequiredArgsConstructor;
import ru.itmo.hhprocess.dto.common.NotificationResponse;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.NotificationEntity;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.entity.VacancyEntity;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.enums.ErrorCode;
import ru.itmo.hhprocess.enums.NotificationType;
import ru.itmo.hhprocess.exception.ApiException;
import ru.itmo.hhprocess.mapper.NotificationMapper;
import ru.itmo.hhprocess.repository.ApplicationRepository;
import ru.itmo.hhprocess.repository.NotificationRepository;
import ru.itmo.hhprocess.repository.VacancyRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final ApplicationRepository applicationRepository;
    private final VacancyRepository vacancyRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final NotificationMapper notificationMapper;

    public record NotificationDecision(String kind, NotificationType type, String recipientRole, String template) {
        public static NotificationDecision parse(String fallbackKind, String code) {
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

        public static NotificationDecision defaultFor(String kind) {
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

        public boolean sendsToCandidate() {
            return recipientRole.contains("CANDIDATE");
        }

        public boolean sendsToRecruiter() {
            return recipientRole.contains("RECRUITER");
        }
    }

    @Transactional
    public void create(UserEntity user, ApplicationEntity application, NotificationType type, String message) {
        NotificationEntity saved = notificationRepository.save(NotificationEntity.builder()
                .user(user)
                .application(application)
                .type(type)
                .message(message)
                .read(false)
                .build());

        NotificationResponse response = notificationMapper.toResponse(saved);
        eventPublisher.publishEvent(new NotificationCreatedEvent(user.getEmail(), response));
    }

    @Transactional
    public void createIfAbsent(UserEntity user, ApplicationEntity application, NotificationType type, String message) {
        if (application != null && notificationRepository.existsByUserIdAndApplicationIdAndType(
                user.getId(), application.getId(), type)) {
            return;
        }
        create(user, application, type, message);
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> getNotificationsForUser(UUID userId) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(notificationMapper::toResponse)
                .toList();
    }

    @Transactional
    public void markAsRead(UUID notificationId, UUID userId) {
        NotificationEntity notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCode.NOTIFICATION_NOT_FOUND, "Notification not found"));

        if (!notification.getUser().getId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.AUTH_ACCESS_DENIED,
                    "Not your notification");
        }

        notification.setRead(true);
        notificationRepository.save(notification);
    }

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
        createIfAbsent(application.getCandidateUser(), application,
                NotificationType.SCREENING_RESULT, "Your application has been rejected");
        return Map.of("notificationSent", true, "status", application.getStatus().name());
    }

    @Transactional
    public Map<String, Object> notifyRecruiter(UUID applicationId) {
        ApplicationEntity application = getApplication(applicationId);
        createIfAbsent(application.getVacancy().getRecruiterUser(), application,
                NotificationType.NEW_APPLICATION,
                "New application received for vacancy: " + application.getVacancy().getTitle());
        return Map.of("notificationSent", true, "status", application.getStatus().name());
    }

    @Transactional
    public Map<String, Object> notifyVacancyClosedCandidates(UUID vacancyId) {
        return notifyVacancyClosedCandidates(vacancyId, new NotificationDecision(
                "VACANCY_CLOSED",
                NotificationType.VACANCY_CLOSED,
                "CANDIDATE",
                "Vacancy was closed: {vacancyTitle}"));
    }

    private Map<String, Object> dispatchNotificationDecision(NotificationDecision decision, UUID applicationId,
                                                             UUID vacancyId, String invitationMessage, String closeReason,
                                                             String cancelReason, String resetReason) {
        if ("VACANCY_CLOSED".equals(decision.kind())) {
            return notifyVacancyClosedCandidates(requiredUuid(vacancyId, "vacancyId"), decision);
        }
        ApplicationEntity application = getApplication(requiredUuid(applicationId, "applicationId"));
        int sent = 0;
        if (decision.sendsToCandidate()) {
            createIfAbsent(application.getCandidateUser(), application,
                    decision.type(), renderTemplate(decision.template(), application, invitationMessage, closeReason, cancelReason, resetReason));
            sent++;
        }
        if (decision.sendsToRecruiter()) {
            createIfAbsent(application.getVacancy().getRecruiterUser(), application,
                    decision.type(), renderTemplate(decision.template(), application, invitationMessage, closeReason, cancelReason, resetReason));
            sent++;
        }
        return Map.of("notificationSent", true, "notifiedCount", sent, "status", application.getStatus().name());
    }

    private Map<String, Object> notifyVacancyClosedCandidates(UUID vacancyId, NotificationDecision decision) {
        VacancyEntity vacancy = vacancyRepository.findByIdForUpdate(vacancyId)
                .orElseThrow(() -> new IllegalArgumentException("Vacancy not found: " + vacancyId));
        List<ApplicationEntity> applications = applicationRepository.findByVacancyIdAndStatusIn(
                vacancyId, List.of(ApplicationStatus.CLOSED_BY_VACANCY));
        int notifiedCount = 0;
        for (ApplicationEntity application : applications) {
            createIfAbsent(application.getCandidateUser(), application,
                    decision.type(), renderTemplate(decision.template(), application, "", "", "", ""));
            notifiedCount++;
        }
        return Map.of("notificationSent", true, "notifiedCount", notifiedCount, "status", vacancy.getStatus().name());
    }

    private ApplicationEntity getApplication(UUID applicationId) {
        return applicationRepository.findDetailedById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Application not found: " + applicationId));
    }

    private String defaultNotificationRecipientRole(String notificationKind) {
        return switch (notificationKind == null ? "" : notificationKind) {
            case "NEW_APPLICATION", "CANDIDATE_RESPONSE" -> "RECRUITER";
            case "TIMEOUT", "ADMIN_RESET" -> "CANDIDATE,RECRUITER";
            default -> "CANDIDATE";
        };
    }

    private String renderTemplate(String template, ApplicationEntity application, String invitationMessage,
                                  String closeReason, String cancelReason, String resetReason) {
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

    private UUID requiredUuid(UUID value, String name) {
        if (value == null) {
            throw new IllegalArgumentException("Camunda notification variable is required: " + name);
        }
        return value;
    }
}
