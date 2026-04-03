package ru.itmo.hhprocess.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.InterviewEntity;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.enums.NotificationType;
import ru.itmo.hhprocess.repository.ApplicationRepository;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class TimeoutBatchProcessor {

    private final ApplicationRepository applicationRepository;
    private final HistoryService historyService;
    private final NotificationService notificationService;
    private final InterviewService interviewService;
    private final ScheduleService scheduleService;

    @Transactional
    public int processExpiredBatch(int batchSize) {
        Instant now = Instant.now();
        List<ApplicationEntity> batch = applicationRepository.findExpiredInvitationsForUpdate(
                ApplicationStatus.INVITED, now, PageRequest.of(0, batchSize));

        log.info("Timeout batch scan at {} found {} expired invitations", now, batch.size());
        if (!batch.isEmpty()) {
            log.info("Timeout batch selected application ids: {}",
                    batch.stream().map(application -> application.getId().toString()).toList());
        }

        for (ApplicationEntity application : batch) {
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

            historyService.record(application, ApplicationStatus.INVITED, ApplicationStatus.CLOSED_BY_TIMEOUT, null);
            notificationService.create(application.getVacancy().getRecruiterUser(), application, NotificationType.INVITATION_TIMEOUT,
                    "Invitation expired for vacancy: " + application.getVacancy().getTitle());
            notificationService.create(application.getCandidateUser(), application, NotificationType.INVITATION_TIMEOUT,
                    "Interview invitation expired for vacancy: " + application.getVacancy().getTitle());

            log.info("Expired invitation application {} marked as CLOSED_BY_TIMEOUT", application.getId());
        }
        return batch.size();
    }
}
