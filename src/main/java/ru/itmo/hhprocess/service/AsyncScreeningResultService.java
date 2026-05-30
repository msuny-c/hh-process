package ru.itmo.hhprocess.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.ScreeningResultEntity;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.enums.NotificationType;
import ru.itmo.hhprocess.enums.ErrorCode;
import ru.itmo.hhprocess.exception.ApiException;
import ru.itmo.hhprocess.repository.ApplicationRepository;
import ru.itmo.hhprocess.repository.ScreeningResultRepository;
import org.springframework.http.HttpStatus;

@Service
@RequiredArgsConstructor
public class AsyncScreeningResultService {

    private final HistoryService historyService;
    private final NotificationAfterCommitService notificationAfterCommitService;
    private final ApplicationRepository applicationRepository;
    private final ScreeningResultRepository screeningResultRepository;


    @Transactional
    public void saveAndApplyFromProcess(
            UUID applicationId,
            boolean passed,
            int score,
            List<String> matchedSkills,
            Map<String, Object> detailsJson,
            Instant screeningStartedAt,
            Instant screeningFinishedAt
    ) {
        ApplicationEntity application = applicationRepository.findDetailedById(applicationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCode.APPLICATION_NOT_FOUND, "Application not found"));

        if (application.getStatus() != ApplicationStatus.SCREENING_IN_PROGRESS) {
            return;
        }

        if (screeningResultRepository.findByApplicationId(application.getId()).isPresent()) {
            return;
        }

        ScreeningResultEntity screeningResult = screeningResultRepository.save(ScreeningResultEntity.builder()
                .application(application)
                .score(score)
                .passed(passed)
                .matchedSkills(matchedSkills == null ? List.of() : matchedSkills)
                .detailsJson(detailsJson == null ? Map.of() : detailsJson)
                .build());

        applyScreeningResult(application, screeningResult, screeningStartedAt, screeningFinishedAt);
    }

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
