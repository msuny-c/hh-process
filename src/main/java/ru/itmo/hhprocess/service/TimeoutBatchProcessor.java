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
    private final NotificationAfterCommitService notificationAfterCommitService;
    private final InterviewService interviewService;
    private final ScheduleService scheduleService;

    @Value("${app.timeout.debug.disable-notifications:false}")
    private boolean disableNotifications;

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
            notificationAfterCommitService.publishAfterCommit(application.getVacancy().getRecruiterUser(), application, NotificationType.INVITATION_TIMEOUT,
                    "Invitation expired for vacancy: " + application.getVacancy().getTitle());
            notificationAfterCommitService.publishAfterCommit(application.getCandidateUser(), application, NotificationType.INVITATION_TIMEOUT,
                    "Interview invitation expired for vacancy: " + application.getVacancy().getTitle());
            log.info("Created timeout notifications for application {}", application.getId());
        }

        log.info("Expired invitation application {} marked as CLOSED_BY_TIMEOUT", application.getId());
        return 1;
    }
}
