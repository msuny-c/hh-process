package ru.itmo.hhprocess.service;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.ScreeningResultEntity;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.enums.NotificationType;
import ru.itmo.hhprocess.messaging.producer.ApplicationScreenedPublisher;
import ru.itmo.hhprocess.messaging.producer.NotificationRequestPublisher;

@Service
@RequiredArgsConstructor
public class AsyncScreeningResultService {

    private final HistoryService historyService;
    private final NotificationRequestPublisher notificationRequestPublisher;
    private final ApplicationScreenedPublisher applicationScreenedPublisher;

    @Transactional
    public void applyScreeningResult(ApplicationEntity application, ScreeningResultEntity screeningResult) {
        Instant now = Instant.now();
        application.setScreeningFinishedAt(now);
        application.setScreeningError(null);

        if (screeningResult.isPassed()) {
            application.setStatus(ApplicationStatus.ON_RECRUITER_REVIEW);
            historyService.record(application,
                    ApplicationStatus.SCREENING_IN_PROGRESS,
                    ApplicationStatus.ON_RECRUITER_REVIEW,
                    null);
            notificationRequestPublisher.publishAfterCommit(
                    application.getVacancy().getRecruiterUser(),
                    application,
                    NotificationType.NEW_APPLICATION,
                    "New application received for vacancy: " + application.getVacancy().getTitle()
            );
        } else {
            application.setStatus(ApplicationStatus.SCREENING_FAILED);
            application.setClosedAt(now);
            historyService.record(application,
                    ApplicationStatus.SCREENING_IN_PROGRESS,
                    ApplicationStatus.SCREENING_FAILED,
                    null);
            notificationRequestPublisher.publishAfterCommit(
                    application.getCandidateUser(),
                    application,
                    NotificationType.SCREENING_RESULT,
                    "Your application has been rejected"
            );
        }

        applicationScreenedPublisher.publishAfterCommit(
                application.getId(),
                screeningResult.isPassed(),
                screeningResult.getScore()
        );
    }
}
