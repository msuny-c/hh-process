package ru.itmo.hhprocess.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.InterviewEntity;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.enums.NotificationType;
import ru.itmo.hhprocess.repository.ApplicationRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class TimeoutBatchProcessor {

    private final ApplicationRepository applicationRepository;
    private final HistoryService historyService;
    private final NotificationService notificationService;
    private final InterviewService interviewService;
    private final ScheduleService scheduleService;
    private final ru.itmo.hhprocess.camunda.CamundaWorkflowFacade camundaWorkflowFacade;

    @Value("${app.timeout.debug.disable-notifications:false}")
    private boolean disableNotifications;


    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public java.util.Map<String, Object> findOneExpiredInvitation() {
        Instant now = Instant.now();
        List<UUID> ids = applicationRepository.findExpiredInvitationIds(
                ApplicationStatus.INVITED, now, PageRequest.of(0, 1));
        if (ids.isEmpty()) {
            return java.util.Map.of("expiredFound", false, "batchClosed", 0);
        }
        return java.util.Map.of("expiredFound", true, "expiredApplicationId", ids.get(0), "batchClosed", 1);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public java.util.Map<String, Object> cancelExpiredInvitationInterview(UUID applicationId) {
        ApplicationEntity application = applicationRepository.findDetailedById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Application not found: " + applicationId));
        InterviewEntity interview = interviewService.findActiveByApplicationId(application.getId()).orElse(null);
        if (interview != null) {
            interviewService.cancel(interview, "Invitation expired");
            return java.util.Map.of("expiredInterviewCancelled", true, "expiredApplicationId", applicationId,
                    "interviewId", interview.getId());
        }
        return java.util.Map.of("expiredInterviewCancelled", false, "expiredApplicationId", applicationId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public java.util.Map<String, Object> releaseExpiredInvitationSlot(UUID applicationId) {
        ApplicationEntity application = applicationRepository.findDetailedById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Application not found: " + applicationId));
        InterviewEntity interview = interviewService.findActiveByApplicationId(application.getId()).orElse(null);
        if (interview != null) {
            scheduleService.releaseForInterview(interview);
            return java.util.Map.of("expiredSlotReleased", true, "expiredApplicationId", applicationId,
                    "interviewId", interview.getId());
        }
        return java.util.Map.of("expiredSlotReleased", false, "expiredApplicationId", applicationId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public java.util.Map<String, Object> closeExpiredInvitationApplication(UUID applicationId) {
        ApplicationEntity application = applicationRepository.findDetailedById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Application not found: " + applicationId));
        if (application.getStatus() == ApplicationStatus.CLOSED_BY_TIMEOUT) {
            return java.util.Map.of("expiredApplicationClosed", true, "expiredApplicationId", applicationId,
                    "status", application.getStatus().name(), "idempotent", true);
        }
        if (application.getStatus() != ApplicationStatus.INVITED) {
            return java.util.Map.of("expiredApplicationClosed", false, "expiredApplicationId", applicationId,
                    "status", application.getStatus().name());
        }
        application.setStatus(ApplicationStatus.CLOSED_BY_TIMEOUT);
        application.setClosedAt(Instant.now());
        applicationRepository.saveAndFlush(application);
        return java.util.Map.of("expiredApplicationClosed", true, "expiredApplicationId", applicationId,
                "status", application.getStatus().name());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public java.util.Map<String, Object> recordExpiredInvitationHistory(UUID applicationId) {
        ApplicationEntity application = applicationRepository.findDetailedById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Application not found: " + applicationId));
        historyService.record(application, ApplicationStatus.INVITED, ApplicationStatus.CLOSED_BY_TIMEOUT, null);
        return java.util.Map.of("expiredHistoryRecorded", true, "expiredApplicationId", applicationId,
                "status", application.getStatus().name());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public java.util.Map<String, Object> notifyExpiredInvitationParticipants(UUID applicationId) {
        ApplicationEntity application = applicationRepository.findDetailedById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Application not found: " + applicationId));
        if (disableNotifications) {
            return java.util.Map.of("expiredNotificationsSent", false, "expiredApplicationId", applicationId,
                    "notificationsDisabled", true);
        }
        notificationService.create(application.getVacancy().getRecruiterUser(), application, NotificationType.INVITATION_TIMEOUT,
                "Invitation expired for vacancy: " + application.getVacancy().getTitle());
        notificationService.create(application.getCandidateUser(), application, NotificationType.INVITATION_TIMEOUT,
                "Interview invitation expired for vacancy: " + application.getVacancy().getTitle());
        return java.util.Map.of("expiredNotificationsSent", true, "expiredApplicationId", applicationId);
    }

    public java.util.Map<String, Object> completeExpiredInvitationProcess(UUID applicationId) {
        applicationRepository.findById(applicationId).ifPresent(camundaWorkflowFacade::invitationTimedOut);
        return java.util.Map.of("expiredProcessCompleted", true, "expiredApplicationId", applicationId,
                "expiredFound", true, "batchClosed", 1);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int processOneExpired() {
        Instant now = Instant.now();
        List<UUID> ids = applicationRepository.findExpiredInvitationIds(
                ApplicationStatus.INVITED, now, PageRequest.of(0, 1));

        log.info("Timeout batch scan at {} found {} expired invitations", now, ids.size());
        if (ids.isEmpty()) {
            return 0;
        }

        UUID applicationId = ids.get(0);
        ApplicationEntity application = applicationRepository.findDetailedById(applicationId).orElse(null);
        if (application == null) {
            log.warn("Expired invitation candidate application {} disappeared before processing", applicationId);
            return 0;
        }

        if (application.getStatus() != ApplicationStatus.INVITED
                || application.getResponseReceivedAt() != null
                || application.getInvitationExpiresAt() == null
                || !application.getInvitationExpiresAt().isBefore(now)) {
            log.info(
                    "Skipping expired invitation applicationId={} after recheck; status={}, invitationExpiresAt={}, responseReceivedAt={}",
                    application.getId(),
                    application.getStatus(),
                    application.getInvitationExpiresAt(),
                    application.getResponseReceivedAt()
            );
            return 0;
        }

        log.info(
                "Processing expired invitation applicationId={}, status={}, invitationExpiresAt={}, responseReceivedAt={}",
                application.getId(),
                application.getStatus(),
                application.getInvitationExpiresAt(),
                application.getResponseReceivedAt()
        );

        InterviewEntity interview = interviewService.findActiveByApplicationId(application.getId()).orElse(null);
        if (interview != null) {
            log.info("Cancelling interview {} for expired application {}", interview.getId(), application.getId());
            interviewService.cancel(interview, "Invitation expired");
            scheduleService.releaseForInterview(interview);
        } else {
            log.info("No active interview found for expired application {}", application.getId());
        }

        application.setStatus(ApplicationStatus.CLOSED_BY_TIMEOUT);
        application.setClosedAt(now);
        applicationRepository.saveAndFlush(application);
        historyService.record(application, ApplicationStatus.INVITED, ApplicationStatus.CLOSED_BY_TIMEOUT, null);

        if (disableNotifications) {
            log.warn("Timeout debug mode: skipping timeout notifications for application {}", application.getId());
        } else {
            log.info("Creating timeout notifications for application {}", application.getId());
            notificationService.create(application.getVacancy().getRecruiterUser(), application, NotificationType.INVITATION_TIMEOUT,
                    "Invitation expired for vacancy: " + application.getVacancy().getTitle());
            notificationService.create(application.getCandidateUser(), application, NotificationType.INVITATION_TIMEOUT,
                    "Interview invitation expired for vacancy: " + application.getVacancy().getTitle());
            log.info("Created timeout notifications for application {}", application.getId());
        }

        camundaWorkflowFacade.invitationTimedOut(application);

        log.info("Expired invitation application {} marked as CLOSED_BY_TIMEOUT", application.getId());
        return 1;
    }
}
