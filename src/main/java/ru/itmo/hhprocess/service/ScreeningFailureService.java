package ru.itmo.hhprocess.service;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.enums.NotificationType;
import ru.itmo.hhprocess.repository.ApplicationRepository;

@Service
@RequiredArgsConstructor
public class ScreeningFailureService {

    private final ApplicationRepository applicationRepository;
    private final HistoryService historyService;
    private final NotificationAfterCommitService notificationAfterCommitService;

    @Transactional
    public void failScreening(ApplicationEntity application, Instant startedAt, Instant failedAt, Exception cause) {
        application.setStatus(ApplicationStatus.SCREENING_ERROR);
        application.setScreeningStartedAt(startedAt);
        application.setScreeningFinishedAt(failedAt);
        application.setScreeningError(cause.getMessage());
        application.setClosedAt(failedAt);
        applicationRepository.save(application);

        historyService.record(
                application,
                ApplicationStatus.SCREENING_IN_PROGRESS,
                ApplicationStatus.SCREENING_ERROR,
                null
        );
        notificationAfterCommitService.publishAfterCommit(
                application.getCandidateUser(),
                application,
                NotificationType.SCREENING_ERROR,
                "We could not process your application screening. Please submit your application again."
        );
    }
}
