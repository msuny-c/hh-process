package ru.itmo.hhprocess.service;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.ScreeningResultEntity;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.enums.NotificationType;

@Service
@RequiredArgsConstructor
public class AsyncScreeningResultService {

    private final HistoryService historyService;
    private final NotificationAfterCommitService notificationAfterCommitService;

    @Transactional
    public void applyScreeningResult(
            ApplicationEntity application,
            ScreeningResultEntity screeningResult,
            Instant screeningStartedAt,
            Instant screeningFinishedAt
    ) {
        application.setScreeningStartedAt(screeningStartedAt);
        application.setScreeningFinishedAt(screeningFinishedAt);
        application.setScreeningError(null);

        if (screeningResult.isPassed()) {
            application.setStatus(ApplicationStatus.ON_RECRUITER_REVIEW);
            historyService.record(application,
                    ApplicationStatus.SCREENING_IN_PROGRESS,
                    ApplicationStatus.ON_RECRUITER_REVIEW,
                    null);
            notificationAfterCommitService.publishAfterCommit(
                    application.getVacancy().getRecruiterUser(),
                    application,
                    NotificationType.NEW_APPLICATION,
                    "New application received for vacancy: " + application.getVacancy().getTitle()
            );
        } else {
            application.setStatus(ApplicationStatus.SCREENING_FAILED);
            application.setClosedAt(screeningFinishedAt);
            historyService.record(application,
                    ApplicationStatus.SCREENING_IN_PROGRESS,
                    ApplicationStatus.SCREENING_FAILED,
                    null);
            notificationAfterCommitService.publishAfterCommit(
                    application.getCandidateUser(),
                    application,
                    NotificationType.SCREENING_RESULT,
                    "Your application has been rejected"
            );
        }
    }
}
