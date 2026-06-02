package ru.itmo.hhprocess.camunda;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.InterviewEntity;
import ru.itmo.hhprocess.entity.InvitationResponseEntity;
import ru.itmo.hhprocess.entity.ScreeningResultEntity;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.entity.VacancyEntity;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.enums.InterviewStatus;
import ru.itmo.hhprocess.enums.NotificationType;
import ru.itmo.hhprocess.enums.ResponseType;
import ru.itmo.hhprocess.enums.VacancyStatus;
import ru.itmo.hhprocess.repository.ApplicationRepository;
import ru.itmo.hhprocess.repository.InvitationResponseRepository;
import ru.itmo.hhprocess.repository.VacancyRepository;
import ru.itmo.hhprocess.service.HistoryService;
import ru.itmo.hhprocess.service.InterviewService;
import ru.itmo.hhprocess.service.NotificationService;
import ru.itmo.hhprocess.service.ScheduleService;
import ru.itmo.hhprocess.service.ScreeningService;
import ru.itmo.hhprocess.service.VacancyHistoryService;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CamundaProcessAdapterService {

    private static final int DEFAULT_DURATION_MINUTES = 60;
    private static final long DEFAULT_DELAY_HOURS = 24;
    private static final long INVITATION_TTL_HOURS = 48;

    private static final List<ApplicationStatus> ACTIVE_APPLICATION_STATUSES = List.of(
            ApplicationStatus.SCREENING_IN_PROGRESS,
            ApplicationStatus.ON_RECRUITER_REVIEW,
            ApplicationStatus.INVITED,
            ApplicationStatus.INVITATION_RESPONDED
    );

    private final ApplicationRepository applicationRepository;
    private final VacancyRepository vacancyRepository;
    private final InvitationResponseRepository invitationResponseRepository;
    private final ScreeningService screeningService;
    private final HistoryService historyService;
    private final VacancyHistoryService vacancyHistoryService;
    private final NotificationService notificationService;
    private final InterviewService interviewService;
    private final ScheduleService scheduleService;

    @Transactional
    public Map<String, Object> autoScreen(UUID applicationId) {
        ApplicationEntity application = getApplication(applicationId);
        ApplicationStatus oldStatus = application.getStatus();
        ScreeningResultEntity screeningResult = screeningService.performScreening(application);

        if (oldStatus == ApplicationStatus.SCREENING_IN_PROGRESS) {
            if (screeningResult.isPassed()) {
                application.setStatus(ApplicationStatus.ON_RECRUITER_REVIEW);
                historyService.record(application, oldStatus, ApplicationStatus.ON_RECRUITER_REVIEW, null);
            } else {
                application.setStatus(ApplicationStatus.SCREENING_FAILED);
                application.setClosedAt(Instant.now());
                historyService.record(application, oldStatus, ApplicationStatus.SCREENING_FAILED, null);
            }
            applicationRepository.save(application);
        }

        boolean passed = application.getStatus() != ApplicationStatus.SCREENING_FAILED;
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("screeningPassed", passed);
        variables.put("status", application.getStatus().name());
        variables.put("screeningScore", screeningResult.getScore());
        variables.put("autoScreeningCompleted", true);
        return variables;
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
    public Map<String, Object> rejectApplication(UUID applicationId, String comment) {
        ApplicationEntity application = getApplication(applicationId);
        ApplicationStatus oldStatus = application.getStatus();
        if (oldStatus == ApplicationStatus.REJECTED_BY_RECRUITER) {
            return Map.of("rejectionPersisted", true, "status", oldStatus.name(), "idempotent", true);
        }
        if (oldStatus.isTerminal()) {
            return Map.of("rejectionPersisted", false, "status", oldStatus.name(), "terminal", true);
        }

        interviewService.findActiveByApplicationId(application.getId()).ifPresent(interview -> {
            interviewService.cancel(interview, comment);
            scheduleService.releaseForInterview(interview);
        });

        application.setStatus(ApplicationStatus.REJECTED_BY_RECRUITER);
        application.setRecruiterComment(comment);
        application.setClosedAt(Instant.now());
        applicationRepository.save(application);

        historyService.record(application, oldStatus, ApplicationStatus.REJECTED_BY_RECRUITER,
                application.getVacancy().getRecruiterUser());
        notificationService.createIfAbsent(application.getCandidateUser(), application,
                NotificationType.APPLICATION_REJECTED, "Your application has been rejected");

        return Map.of("rejectionPersisted", true, "status", application.getStatus().name());
    }

    @Transactional
    public Map<String, Object> persistInvitation(UUID applicationId, String message, Instant scheduledAt,
                                                 int durationMinutes) {
        ApplicationEntity application = getApplication(applicationId);
        ApplicationStatus oldStatus = application.getStatus();
        InterviewEntity activeInterview = interviewService.findActiveByApplicationId(application.getId()).orElse(null);
        if (application.getStatus() == ApplicationStatus.INVITED && activeInterview != null) {
            return Map.of(
                    "invitationPersisted", true,
                    "status", application.getStatus().name(),
                    "interviewId", activeInterview.getId(),
                    "idempotent", true
            );
        }
        if (application.getStatus() != ApplicationStatus.ON_RECRUITER_REVIEW) {
            return Map.of("invitationPersisted", false, "status", application.getStatus().name());
        }

        Instant now = Instant.now();
        Instant expiresAt = now.plus(INVITATION_TTL_HOURS, ChronoUnit.HOURS);
        UserEntity recruiterUser = application.getVacancy().getRecruiterUser();

        application.setStatus(ApplicationStatus.INVITED);
        application.setInvitationText(message);
        application.setInvitationSentAt(now);
        application.setInvitationExpiresAt(expiresAt);
        applicationRepository.save(application);

        InterviewEntity interview = interviewService.createScheduledInterview(
                application, recruiterUser, scheduledAt, durationMinutes, message);
        var slot = scheduleService.reserveOnTheFly(recruiterUser, interview, scheduledAt, durationMinutes);

        historyService.record(application, oldStatus, ApplicationStatus.INVITED, recruiterUser);
        notificationService.createIfAbsent(application.getCandidateUser(), application,
                NotificationType.INVITATION, "You have been invited to an interview: " + message);

        return Map.of(
                "invitationPersisted", true,
                "status", application.getStatus().name(),
                "interviewId", interview.getId(),
                "scheduleSlotId", slot.getId(),
                "invitationExpiresAt", expiresAt
        );
    }

    @Transactional
    public Map<String, Object> persistCandidateResponse(UUID applicationId, ResponseType responseType, String message) {
        ApplicationEntity application = getApplication(applicationId);
        if (invitationResponseRepository.findByApplicationId(applicationId).isPresent()) {
            return Map.of("responsePersisted", true, "status", application.getStatus().name(), "idempotent", true);
        }
        if (application.getStatus() != ApplicationStatus.INVITED) {
            return Map.of("responsePersisted", false, "status", application.getStatus().name());
        }

        invitationResponseRepository.save(InvitationResponseEntity.builder()
                .application(application)
                .candidateUser(application.getCandidateUser())
                .responseType(responseType)
                .message(message)
                .build());

        application.setStatus(ApplicationStatus.INVITATION_RESPONDED);
        application.setResponseReceivedAt(Instant.now());
        applicationRepository.save(application);
        historyService.record(application, ApplicationStatus.INVITED, ApplicationStatus.INVITATION_RESPONDED,
                application.getCandidateUser());
        notificationService.createIfAbsent(application.getVacancy().getRecruiterUser(), application,
                NotificationType.INVITATION_RESPONSE, "Candidate responded to interview invitation");

        return Map.of("responsePersisted", true, "status", application.getStatus().name());
    }

    @Transactional
    public Map<String, Object> closeByTimeout(UUID applicationId) {
        ApplicationEntity application = getApplication(applicationId);
        if (application.getStatus() == ApplicationStatus.CLOSED_BY_TIMEOUT) {
            return Map.of("timeoutClosed", true, "status", application.getStatus().name(), "idempotent", true);
        }
        if (application.getStatus() != ApplicationStatus.INVITED) {
            return Map.of("timeoutClosed", false, "status", application.getStatus().name());
        }

        interviewService.findActiveByApplicationId(application.getId()).ifPresent(interview -> {
            interviewService.cancel(interview, "Invitation expired");
            scheduleService.releaseForInterview(interview);
        });

        application.setStatus(ApplicationStatus.CLOSED_BY_TIMEOUT);
        application.setClosedAt(Instant.now());
        applicationRepository.save(application);
        historyService.record(application, ApplicationStatus.INVITED, ApplicationStatus.CLOSED_BY_TIMEOUT, null);
        notificationService.createIfAbsent(application.getVacancy().getRecruiterUser(), application,
                NotificationType.INVITATION_TIMEOUT,
                "Invitation expired for vacancy: " + application.getVacancy().getTitle());
        notificationService.createIfAbsent(application.getCandidateUser(), application,
                NotificationType.INVITATION_TIMEOUT,
                "Interview invitation expired for vacancy: " + application.getVacancy().getTitle());

        return Map.of("timeoutClosed", true, "status", application.getStatus().name());
    }

    @Transactional
    public Map<String, Object> closeVacancyApplications(UUID vacancyId, String reason) {
        VacancyEntity vacancy = vacancyRepository.findByIdForUpdate(vacancyId)
                .orElseThrow(() -> new IllegalArgumentException("Vacancy not found: " + vacancyId));
        VacancyStatus oldVacancyStatus = vacancy.getStatus();
        if (oldVacancyStatus != VacancyStatus.CLOSED) {
            vacancy.setStatus(VacancyStatus.CLOSED);
            vacancyHistoryService.record(vacancy, oldVacancyStatus, VacancyStatus.CLOSED, vacancy.getRecruiterUser());
        }

        List<ApplicationEntity> applications = applicationRepository.findByVacancyIdAndStatusIn(
                vacancyId, ACTIVE_APPLICATION_STATUSES);
        int closedCount = 0;
        Instant now = Instant.now();
        for (ApplicationEntity application : applications) {
            ApplicationStatus oldStatus = application.getStatus();
            if (oldStatus == ApplicationStatus.CLOSED_BY_VACANCY) {
                continue;
            }
            interviewService.findActiveByApplicationId(application.getId()).ifPresent(interview -> {
                interview.setStatus(InterviewStatus.CANCELLED);
                interview.setCancelReason(reason);
                interview.setCancelledAt(now);
                scheduleService.releaseForInterview(interview);
            });
            application.setStatus(ApplicationStatus.CLOSED_BY_VACANCY);
            application.setClosedAt(now);
            application.setInvitationText(null);
            application.setInvitationSentAt(null);
            application.setInvitationExpiresAt(null);
            application.setResponseReceivedAt(null);
            application.setRecruiterComment(reason);
            historyService.record(application, oldStatus, ApplicationStatus.CLOSED_BY_VACANCY, vacancy.getRecruiterUser());
            notificationService.createIfAbsent(application.getCandidateUser(), application,
                    NotificationType.VACANCY_CLOSED, "Vacancy was closed: " + vacancy.getTitle());
            closedCount++;
        }
        return Map.of("vacancyClosed", true, "closedApplicationsCount", closedCount, "status", vacancy.getStatus().name());
    }

    public Instant scheduledAtOrDefault(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return Instant.now().plus(DEFAULT_DELAY_HOURS, ChronoUnit.HOURS);
        }
        String raw = String.valueOf(value);
        try {
            return Instant.parse(raw);
        } catch (RuntimeException ignored) {
            return OffsetDateTime.parse(raw).toInstant();
        }
    }

    public int durationOrDefault(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return DEFAULT_DURATION_MINUTES;
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private ApplicationEntity getApplication(UUID applicationId) {
        return applicationRepository.findDetailedById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Application not found: " + applicationId));
    }
}
