package ru.itmo.hhprocess.camunda;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.InterviewEntity;
import ru.itmo.hhprocess.entity.InvitationResponseEntity;
import ru.itmo.hhprocess.entity.ScreeningResultEntity;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.entity.VacancyEntity;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.enums.ErrorCode;
import ru.itmo.hhprocess.enums.InterviewStatus;
import ru.itmo.hhprocess.enums.NotificationType;
import ru.itmo.hhprocess.enums.ResponseType;
import ru.itmo.hhprocess.enums.VacancyStatus;
import ru.itmo.hhprocess.repository.ApplicationRepository;
import ru.itmo.hhprocess.repository.InvitationResponseRepository;
import ru.itmo.hhprocess.repository.UserRepository;
import ru.itmo.hhprocess.repository.VacancyRepository;
import ru.itmo.hhprocess.exception.ApiException;
import ru.itmo.hhprocess.service.HistoryService;
import ru.itmo.hhprocess.service.InterviewService;
import ru.itmo.hhprocess.service.NotificationService;
import ru.itmo.hhprocess.service.ScheduleService;
import ru.itmo.hhprocess.service.ScreeningService;
import ru.itmo.hhprocess.service.TimeoutService;
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
    private static final int UI_PAYLOAD_MAX_LENGTH = 3500;

    private static final List<ApplicationStatus> ACTIVE_APPLICATION_STATUSES = List.of(
            ApplicationStatus.SCREENING_IN_PROGRESS,
            ApplicationStatus.ON_RECRUITER_REVIEW,
            ApplicationStatus.INVITED,
            ApplicationStatus.INVITATION_RESPONDED
    );

    private final ApplicationRepository applicationRepository;
    private final VacancyRepository vacancyRepository;
    private final UserRepository userRepository;
    private final InvitationResponseRepository invitationResponseRepository;
    private final ScreeningService screeningService;
    private final HistoryService historyService;
    private final VacancyHistoryService vacancyHistoryService;
    private final NotificationService notificationService;
    private final InterviewService interviewService;
    private final ScheduleService scheduleService;
    private final TimeoutService timeoutService;
    private final CamundaRestClient camundaRestClient;
    private final ObjectMapper objectMapper;

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

        return Map.of("rejectionPersisted", true, "status", application.getStatus().name());
    }

    @Transactional
    public Map<String, Object> notifyApplicationRejected(UUID applicationId) {
        ApplicationEntity application = getApplication(applicationId);
        notificationService.createIfAbsent(application.getCandidateUser(), application,
                NotificationType.APPLICATION_REJECTED, "Your application has been rejected");
        return Map.of("notificationSent", true, "status", application.getStatus().name());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateInvitationForm(UUID applicationId, String message, Instant scheduledAt,
                                                       int durationMinutes) {
        if (message == null || message.isBlank()) {
            throw new CamundaFormValidationException("Invitation message must not be blank");
        }
        if (message.length() > 5_000) {
            throw new CamundaFormValidationException("Invitation message must be at most 5000 characters");
        }
        if (scheduledAt == null) {
            throw new CamundaFormValidationException("Interview date/time is required");
        }
        if (scheduledAt.isBefore(Instant.now())) {
            throw new CamundaFormValidationException("Interview date/time must be in the future");
        }
        if (durationMinutes < 15 || durationMinutes > 480) {
            throw new CamundaFormValidationException("Duration must be between 15 and 480 minutes");
        }

        ApplicationEntity application = getApplication(applicationId);
        if (application.getStatus() != ApplicationStatus.ON_RECRUITER_REVIEW
                && application.getStatus() != ApplicationStatus.INVITED) {
            throw new CamundaFormValidationException("Application is not ready for invitation: "
                    + application.getStatus());
        }

        try {
            scheduleService.ensureAvailable(application.getVacancy().getRecruiterUser(), scheduledAt, durationMinutes);
        } catch (ApiException e) {
            throw new CamundaFormValidationException(e.getMessage());
        }

        return Map.of(
                "formValidated", true,
                "formErrorMessage", "",
                "scheduledAt", scheduledAt,
                "durationMinutes", durationMinutes
        );
    }

    @Transactional
    public Map<String, Object> persistInvitation(UUID applicationId, String message, Instant scheduledAt,
                                                 int durationMinutes) {
        validateInvitationForm(applicationId, message, scheduledAt, durationMinutes);
        Map<String, Object> result = new LinkedHashMap<>(saveInvitationToDb(applicationId, message));
        result.putAll(createInvitationInterview(applicationId, message, scheduledAt, durationMinutes));
        UUID interviewId = (UUID) result.get("interviewId");
        result.putAll(reserveInvitationSlot(applicationId, interviewId, scheduledAt, durationMinutes));
        result.putAll(recordInvitationHistory(applicationId));
        result.put("invitationPersisted", true);
        return result;
    }

    @Transactional
    public Map<String, Object> saveInvitationToDb(UUID applicationId, String message) {
        ApplicationEntity application = getApplication(applicationId);
        ApplicationStatus oldStatus = application.getStatus();
        if (oldStatus == ApplicationStatus.INVITED) {
            return Map.of(
                    "invitationSaved", true,
                    "status", application.getStatus().name(),
                    "idempotent", true
            );
        }
        if (oldStatus != ApplicationStatus.ON_RECRUITER_REVIEW) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_APPLICATION_STATE,
                    "Application is not ready for invitation: " + oldStatus);
        }

        Instant now = Instant.now();
        Instant expiresAt = now.plus(INVITATION_TTL_HOURS, ChronoUnit.HOURS);
        application.setStatus(ApplicationStatus.INVITED);
        application.setInvitationText(message);
        application.setInvitationSentAt(now);
        application.setInvitationExpiresAt(expiresAt);
        applicationRepository.save(application);

        return Map.of(
                "invitationSaved", true,
                "oldApplicationStatus", oldStatus.name(),
                "status", application.getStatus().name(),
                "invitationExpiresAt", expiresAt
        );
    }

    @Transactional
    public Map<String, Object> createInvitationInterview(UUID applicationId, String message, Instant scheduledAt,
                                                         int durationMinutes) {
        ApplicationEntity application = getApplication(applicationId);
        InterviewEntity activeInterview = interviewService.findActiveByApplicationId(application.getId()).orElse(null);
        if (activeInterview != null) {
            return Map.of(
                    "interviewCreated", true,
                    "interviewId", activeInterview.getId(),
                    "idempotent", true
            );
        }
        if (application.getStatus() != ApplicationStatus.INVITED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_APPLICATION_STATE,
                    "Interview can be created only for invited application: " + application.getStatus());
        }

        UserEntity recruiterUser = application.getVacancy().getRecruiterUser();
        InterviewEntity interview = interviewService.createScheduledInterview(
                application, recruiterUser, scheduledAt, durationMinutes, message);
        return Map.of("interviewCreated", true, "interviewId", interview.getId());
    }

    @Transactional
    public Map<String, Object> reserveInvitationSlot(UUID applicationId, UUID interviewId, Instant scheduledAt,
                                                     int durationMinutes) {
        if (interviewId == null) {
            throw new IllegalArgumentException("interviewId is required to reserve a schedule slot");
        }
        InterviewEntity interview = interviewService.getByIdForUpdate(interviewId);
        var existingSlot = scheduleService.findByInterviewId(interview);
        if (existingSlot != null) {
            return Map.of(
                    "slotReserved", true,
                    "scheduleSlotId", existingSlot.getId(),
                    "idempotent", true
            );
        }
        var slot = scheduleService.reserveOnTheFly(
                interview.getRecruiterUser(), interview, scheduledAt, durationMinutes);
        return Map.of("slotReserved", true, "scheduleSlotId", slot.getId(), "applicationId", applicationId);
    }

    @Transactional
    public Map<String, Object> recordInvitationHistory(UUID applicationId) {
        ApplicationEntity application = getApplication(applicationId);
        if (application.getStatus() != ApplicationStatus.INVITED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_APPLICATION_STATE,
                    "Invitation history can be recorded only for invited application: " + application.getStatus());
        }
        historyService.record(application, ApplicationStatus.ON_RECRUITER_REVIEW, ApplicationStatus.INVITED,
                application.getVacancy().getRecruiterUser());
        return Map.of("invitationHistoryRecorded", true, "status", application.getStatus().name());
    }

    @Transactional
    public Map<String, Object> notifyInvitation(UUID applicationId, String message) {
        ApplicationEntity application = getApplication(applicationId);
        notificationService.createIfAbsent(application.getCandidateUser(), application,
                NotificationType.INVITATION, "You have been invited to an interview: " + message);
        return Map.of("notificationSent", true, "status", application.getStatus().name());
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

        return Map.of("responsePersisted", true, "status", application.getStatus().name());
    }

    @Transactional
    public Map<String, Object> notifyCandidateResponse(UUID applicationId) {
        ApplicationEntity application = getApplication(applicationId);
        notificationService.createIfAbsent(application.getVacancy().getRecruiterUser(), application,
                NotificationType.INVITATION_RESPONSE, "Candidate responded to interview invitation");
        return Map.of("notificationSent", true, "status", application.getStatus().name());
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
        Map<String, Object> dbResult = closeVacancyApplicationsInDb(vacancyId, reason);
        notifyVacancyClosedCandidates(vacancyId);
        correlateVacancyClosedApplications(vacancyId, reason);
        return dbResult;
    }

    @Transactional
    public Map<String, Object> closeVacancyApplicationsInDb(UUID vacancyId, String reason) {
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
            closedCount++;
        }
        return Map.of("vacancyClosed", true, "closedApplicationsCount", closedCount, "status", vacancy.getStatus().name());
    }


    @Transactional
    public Map<String, Object> correlateVacancyClosedApplications(UUID vacancyId, String reason) {
        VacancyEntity vacancy = vacancyRepository.findByIdForUpdate(vacancyId)
                .orElseThrow(() -> new IllegalArgumentException("Vacancy not found: " + vacancyId));
        List<ApplicationEntity> applications = applicationRepository.findByVacancyIdAndStatusIn(
                vacancyId, List.of(ApplicationStatus.CLOSED_BY_VACANCY));
        int correlated = 0;
        int missed = 0;
        for (ApplicationEntity application : applications) {
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("applicationId", application.getId());
            variables.put("vacancyId", vacancyId);
            variables.put("vacancyTitle", vacancy.getTitle());
            variables.put("closeReason", reason == null ? "" : reason);
            variables.put("status", application.getStatus().name());
            boolean sent = camundaRestClient.correlateMessage("MSG_VACANCY_CLOSED", "application:" + application.getId(), variables);
            if (sent) {
                correlated++;
            } else {
                missed++;
            }
        }
        return Map.of(
                "vacancyClosedMessageCorrelated", true,
                "correlatedApplications", correlated,
                "missedApplications", missed
        );
    }

    @Transactional
    public Map<String, Object> handleVacancyClosedMessage(UUID applicationId, String reason) {
        ApplicationEntity application = getApplication(applicationId);
        ApplicationStatus oldStatus = application.getStatus();
        if (oldStatus != ApplicationStatus.CLOSED_BY_VACANCY && !oldStatus.isTerminal()) {
            application.setStatus(ApplicationStatus.CLOSED_BY_VACANCY);
            application.setClosedAt(Instant.now());
            application.setRecruiterComment(reason);
            applicationRepository.save(application);
            historyService.record(application, oldStatus, ApplicationStatus.CLOSED_BY_VACANCY,
                    application.getVacancy().getRecruiterUser());
        }
        return Map.of(
                "vacancyClosedMessageHandled", true,
                "applicationId", application.getId(),
                "oldApplicationStatus", oldStatus.name(),
                "status", application.getStatus().name()
        );
    }

    @Transactional
    public Map<String, Object> notifyVacancyClosedCandidates(UUID vacancyId) {
        VacancyEntity vacancy = vacancyRepository.findByIdForUpdate(vacancyId)
                .orElseThrow(() -> new IllegalArgumentException("Vacancy not found: " + vacancyId));
        List<ApplicationEntity> applications = applicationRepository.findByVacancyIdAndStatusIn(
                vacancyId, List.of(ApplicationStatus.CLOSED_BY_VACANCY));
        int notifiedCount = 0;
        for (ApplicationEntity application : applications) {
            notificationService.createIfAbsent(application.getCandidateUser(), application,
                    NotificationType.VACANCY_CLOSED, "Vacancy was closed: " + vacancy.getTitle());
            notifiedCount++;
        }
        return Map.of("notificationSent", true, "notifiedCount", notifiedCount, "status", vacancy.getStatus().name());
    }

    @Transactional
    public Map<String, Object> resetInterviewByAdmin(UUID interviewId, UUID adminUserId, String reason) {
        Map<String, Object> dbResult = resetInterviewByAdminInDb(interviewId, adminUserId, reason);
        UUID applicationId = (UUID) dbResult.get("applicationId");
        Map<String, Object> notificationResult = notifyAdminInterviewReset(applicationId, reason);
        Map<String, Object> result = new LinkedHashMap<>(dbResult);
        result.putAll(notificationResult);
        return result;
    }

    @Transactional
    public Map<String, Object> resetInterviewByAdminInDb(UUID interviewId, UUID adminUserId, String reason) {
        InterviewEntity interview = interviewService.getByIdForUpdate(interviewId);
        UserEntity adminUser = userRepository.findById(adminUserId)
                .orElseThrow(() -> new IllegalArgumentException("Admin user not found: " + adminUserId));
        ApplicationEntity application = interview.getApplication();
        ApplicationStatus oldStatus = application.getStatus();
        if (interview.getStatus() == InterviewStatus.CANCELLED) {
            return Map.of(
                    "interviewReset", true,
                    "interviewId", interview.getId(),
                    "applicationId", application.getId(),
                    "status", interview.getStatus().name(),
                    "idempotent", true
            );
        }
        if (interview.getStatus() != InterviewStatus.SCHEDULED) {
            throw new IllegalArgumentException("Interview is not active: " + interviewId);
        }

        interviewService.cancel(interview, reason);
        scheduleService.releaseForInterview(interview);

        application.setStatus(ApplicationStatus.ON_RECRUITER_REVIEW);
        application.setInvitationText(null);
        application.setInvitationSentAt(null);
        application.setInvitationExpiresAt(null);
        application.setResponseReceivedAt(null);
        application.setRecruiterComment(reason);
        applicationRepository.save(application);

        historyService.record(application, oldStatus, ApplicationStatus.ON_RECRUITER_REVIEW, adminUser);

        return Map.of(
                "interviewReset", true,
                "interviewId", interview.getId(),
                "applicationId", application.getId(),
                "status", interview.getStatus().name(),
                "applicationStatus", application.getStatus().name()
        );
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
    public Map<String, Object> rollbackApplicationTransaction(UUID applicationId, String reason) {
        ApplicationEntity application = getApplication(applicationId);
        ApplicationStatus oldStatus = application.getStatus();
        interviewService.findActiveByApplicationId(application.getId()).ifPresent(interview -> {
            interviewService.cancel(interview, reason);
            scheduleService.releaseForInterview(interview);
        });
        if (oldStatus == ApplicationStatus.INVITED || oldStatus == ApplicationStatus.INVITATION_RESPONDED) {
            application.setStatus(ApplicationStatus.ON_RECRUITER_REVIEW);
            application.setInvitationText(null);
            application.setInvitationSentAt(null);
            application.setInvitationExpiresAt(null);
            application.setResponseReceivedAt(null);
            application.setRecruiterComment(reason);
            applicationRepository.save(application);
            historyService.record(application, oldStatus, ApplicationStatus.ON_RECRUITER_REVIEW,
                    application.getVacancy().getRecruiterUser());
        }
        return Map.of(
                "rollbackCompleted", true,
                "applicationId", application.getId(),
                "oldStatus", oldStatus.name(),
                "status", application.getStatus().name()
        );
    }

    @Transactional
    public Map<String, Object> rollbackVacancyTransaction(UUID vacancyId, String reason) {
        VacancyEntity vacancy = vacancyRepository.findByIdForUpdate(vacancyId)
                .orElseThrow(() -> new IllegalArgumentException("Vacancy not found: " + vacancyId));
        return Map.of(
                "rollbackCompleted", true,
                "vacancyId", vacancy.getId(),
                "status", vacancy.getStatus().name(),
                "rollbackReason", reason == null ? "" : reason
        );
    }


    @Transactional(readOnly = true)
    public Map<String, Object> validateApplyToVacancyForm(UUID applicationId, UUID vacancyId, UUID candidateUserId,
                                                          String starterUserId, String resumeText, String coverLetter) {
        if (resumeText == null || resumeText.isBlank()) {
            throw new CamundaFormValidationException("Resume text must not be blank");
        }
        if (resumeText.length() < 20 || resumeText.length() > 50_000) {
            throw new CamundaFormValidationException("Resume text length must be between 20 and 50000 characters");
        }
        if (coverLetter != null && coverLetter.length() > 10_000) {
            throw new CamundaFormValidationException("Cover letter must be at most 10000 characters");
        }
        if (applicationId != null) {
            getApplication(applicationId);
            return Map.of("formValidated", true, "formErrorMessage", "", "applicationId", applicationId);
        }

        VacancyEntity vacancy = vacancyRepository.findByIdForUpdate(vacancyId)
                .orElseThrow(() -> new CamundaFormValidationException("Vacancy not found: " + vacancyId));
        if (vacancy.getStatus() != VacancyStatus.ACTIVE) {
            throw new CamundaFormValidationException("Vacancy is not active");
        }
        UserEntity candidate = candidateUserId == null
                ? resolveUserFromCamundaStarter(starterUserId, "CANDIDATE")
                : userRepository.findById(candidateUserId)
                .orElseThrow(() -> new CamundaFormValidationException("Candidate user not found: " + candidateUserId));
        if (!hasRole(candidate, "CANDIDATE")) {
            throw new CamundaFormValidationException("Only CANDIDATE users can apply to vacancies");
        }
        if (applicationRepository.existsByCandidateUserIdAndVacancyId(candidate.getId(), vacancy.getId())) {
            throw new CamundaFormValidationException("You already have an application for this vacancy");
        }
        return Map.of(
                "formValidated", true,
                "formErrorMessage", "",
                "vacancyId", vacancy.getId(),
                "candidateUserId", candidate.getId(),
                "recruiterUserId", vacancy.getRecruiterUser().getId(),
                "vacancyTitle", vacancy.getTitle()
        );
    }

    @Transactional
    public Map<String, Object> createApplicationFromCamundaForm(UUID existingApplicationId, UUID vacancyId, UUID candidateUserId,
                                                                String starterUserId, String resumeText, String coverLetter,
                                                                String processInstanceId) {
        if (existingApplicationId != null) {
            ApplicationEntity application = getApplication(existingApplicationId);
            if (application.getCamundaProcessInstanceId() == null || application.getCamundaProcessInstanceId().isBlank()) {
                application.setCamundaProcessInstanceId(processInstanceId);
                applicationRepository.save(application);
            }
            String businessKey = "application:" + application.getId();
            camundaRestClient.updateProcessInstanceBusinessKey(processInstanceId, businessKey);
            return Map.of(
                    "applicationCreated", true,
                    "applicationId", application.getId(),
                    "status", application.getStatus().name(),
                    "idempotent", true
            );
        }
        if (processInstanceId != null && !processInstanceId.isBlank()) {
            var existing = applicationRepository.findByCamundaProcessInstanceId(processInstanceId);
            if (existing.isPresent()) {
                ApplicationEntity application = existing.get();
                return Map.of(
                        "applicationCreated", true,
                        "applicationId", application.getId(),
                        "status", application.getStatus().name(),
                        "idempotent", true
                );
            }
        }
        validateApplyToVacancyForm(null, vacancyId, candidateUserId, starterUserId, resumeText, coverLetter);
        VacancyEntity vacancy = vacancyRepository.findByIdForUpdate(vacancyId)
                .orElseThrow(() -> new CamundaFormValidationException("Vacancy not found: " + vacancyId));
        UserEntity candidate = candidateUserId == null
                ? resolveUserFromCamundaStarter(starterUserId, "CANDIDATE")
                : userRepository.findById(candidateUserId)
                .orElseThrow(() -> new CamundaFormValidationException("Candidate user not found: " + candidateUserId));

        ApplicationEntity application = applicationRepository.save(ApplicationEntity.builder()
                .vacancy(vacancy)
                .candidateUser(candidate)
                .resumeText(resumeText)
                .coverLetter(coverLetter)
                .status(ApplicationStatus.SCREENING_IN_PROGRESS)
                .camundaProcessInstanceId(processInstanceId)
                .build());
        historyService.record(application, null, ApplicationStatus.SCREENING_IN_PROGRESS, null);

        String businessKey = "application:" + application.getId();
        camundaRestClient.updateProcessInstanceBusinessKey(processInstanceId, businessKey);

        return Map.of(
                "applicationCreated", true,
                "applicationId", application.getId(),
                "vacancyId", vacancy.getId(),
                "candidateUserId", candidate.getId(),
                "recruiterUserId", vacancy.getRecruiterUser().getId(),
                "vacancyTitle", vacancy.getTitle(),
                "status", application.getStatus().name(),
                "applicationBusinessKey", businessKey
        );
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateRecruiterDecisionForm(UUID applicationId, String decision, String comment) {
        if (decision == null || decision.isBlank()) {
            throw new CamundaFormValidationException("Recruiter decision is required");
        }
        if (!List.of("INVITE", "REJECT", "VACANCY_CLOSED").contains(decision)) {
            throw new CamundaFormValidationException("Recruiter decision must be INVITE, REJECT or VACANCY_CLOSED");
        }
        if ("REJECT".equals(decision) && (comment == null || comment.isBlank())) {
            throw new CamundaFormValidationException("Rejection comment is required");
        }
        if (comment != null && comment.length() > 5_000) {
            throw new CamundaFormValidationException("Recruiter comment must be at most 5000 characters");
        }
        ApplicationEntity application = getApplication(applicationId);
        if (application.getStatus() != ApplicationStatus.ON_RECRUITER_REVIEW) {
            throw new CamundaFormValidationException("Application is not waiting for recruiter decision: " + application.getStatus());
        }
        return Map.of("formValidated", true, "formErrorMessage", "");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateCandidateResponseForm(UUID applicationId, String responseType, String message) {
        requiredResponseType(responseType);
        if (message != null && message.length() > 5_000) {
            throw new CamundaFormValidationException("Candidate response message must be at most 5000 characters");
        }
        ApplicationEntity application = getApplication(applicationId);
        if (application.getStatus() != ApplicationStatus.INVITED) {
            throw new CamundaFormValidationException("Application is not waiting for candidate response: " + application.getStatus());
        }
        return Map.of("formValidated", true, "formErrorMessage", "");
    }

    public ResponseType requiredResponseType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new CamundaFormValidationException("Candidate response type is required");
        }
        try {
            ResponseType type = ResponseType.valueOf(raw);
            if (!List.of(ResponseType.ACCEPT, ResponseType.DECLINE, ResponseType.OTHER).contains(type)) {
                throw new CamundaFormValidationException("Candidate response type must be ACCEPT, DECLINE or OTHER");
            }
            return type;
        } catch (IllegalArgumentException e) {
            throw new CamundaFormValidationException("Candidate response type must be ACCEPT, DECLINE or OTHER");
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateRejectionAllowed(UUID applicationId, String comment) {
        if (comment == null || comment.isBlank()) {
            throw new CamundaFormValidationException("Rejection comment is required");
        }
        if (comment.length() > 5_000) {
            throw new CamundaFormValidationException("Rejection comment must be at most 5000 characters");
        }
        ApplicationEntity application = getApplication(applicationId);
        if (application.getStatus() != ApplicationStatus.ON_RECRUITER_REVIEW
                && application.getStatus() != ApplicationStatus.INVITED
                && application.getStatus() != ApplicationStatus.INVITATION_RESPONDED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_APPLICATION_STATE,
                    "Application cannot be rejected from state: " + application.getStatus());
        }
        return Map.of("rejectionAllowed", true, "oldApplicationStatus", application.getStatus().name());
    }

    @Transactional
    public Map<String, Object> cancelRejectionInterviewIfAny(UUID applicationId, String comment) {
        ApplicationEntity application = getApplication(applicationId);
        interviewService.findActiveByApplicationId(application.getId()).ifPresent(interview -> {
            interviewService.cancel(interview, comment);
            scheduleService.releaseForInterview(interview);
        });
        return Map.of("rejectionInterviewCancelled", true);
    }

    @Transactional
    public Map<String, Object> markApplicationRejected(UUID applicationId, String comment) {
        ApplicationEntity application = getApplication(applicationId);
        ApplicationStatus oldStatus = application.getStatus();
        if (oldStatus == ApplicationStatus.REJECTED_BY_RECRUITER) {
            return Map.of("applicationRejected", true, "status", application.getStatus().name(), "idempotent", true);
        }
        application.setStatus(ApplicationStatus.REJECTED_BY_RECRUITER);
        application.setRecruiterComment(comment);
        application.setClosedAt(Instant.now());
        applicationRepository.save(application);
        return Map.of("applicationRejected", true, "oldApplicationStatus", oldStatus.name(), "status", application.getStatus().name());
    }

    @Transactional
    public Map<String, Object> recordRejectionHistory(UUID applicationId) {
        ApplicationEntity application = getApplication(applicationId);
        historyService.record(application, ApplicationStatus.ON_RECRUITER_REVIEW, ApplicationStatus.REJECTED_BY_RECRUITER,
                application.getVacancy().getRecruiterUser());
        return Map.of("rejectionHistoryRecorded", true, "status", application.getStatus().name());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> checkInvitationStillActive(UUID applicationId) {
        ApplicationEntity application = getApplication(applicationId);
        if (application.getStatus() != ApplicationStatus.INVITED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_APPLICATION_STATE,
                    "Application is not waiting for candidate response: " + application.getStatus());
        }
        if (invitationResponseRepository.findByApplicationId(applicationId).isPresent()) {
            return Map.of("invitationActive", true, "idempotent", true, "status", application.getStatus().name());
        }
        return Map.of("invitationActive", true, "status", application.getStatus().name());
    }

    @Transactional
    public Map<String, Object> saveCandidateResponse(UUID applicationId, ResponseType responseType, String message) {
        ApplicationEntity application = getApplication(applicationId);
        if (invitationResponseRepository.findByApplicationId(applicationId).isPresent()) {
            return Map.of("candidateResponseSaved", true, "idempotent", true, "status", application.getStatus().name());
        }
        invitationResponseRepository.save(InvitationResponseEntity.builder()
                .application(application)
                .candidateUser(application.getCandidateUser())
                .responseType(responseType)
                .message(message)
                .build());
        return Map.of("candidateResponseSaved", true, "responseType", responseType.name());
    }

    @Transactional
    public Map<String, Object> markCandidateResponseReceived(UUID applicationId) {
        ApplicationEntity application = getApplication(applicationId);
        ApplicationStatus oldStatus = application.getStatus();
        if (oldStatus == ApplicationStatus.INVITATION_RESPONDED) {
            return Map.of("candidateResponseMarked", true, "status", application.getStatus().name(), "idempotent", true);
        }
        if (oldStatus != ApplicationStatus.INVITED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_APPLICATION_STATE,
                    "Candidate response can be marked only for invited application: " + oldStatus);
        }
        application.setStatus(ApplicationStatus.INVITATION_RESPONDED);
        application.setResponseReceivedAt(Instant.now());
        applicationRepository.save(application);
        return Map.of("candidateResponseMarked", true, "oldApplicationStatus", oldStatus.name(), "status", application.getStatus().name());
    }

    @Transactional
    public Map<String, Object> recordCandidateResponseHistory(UUID applicationId) {
        ApplicationEntity application = getApplication(applicationId);
        historyService.record(application, ApplicationStatus.INVITED, ApplicationStatus.INVITATION_RESPONDED,
                application.getCandidateUser());
        return Map.of("candidateResponseHistoryRecorded", true, "status", application.getStatus().name());
    }


    @Transactional(readOnly = true)
    public Map<String, Object> validateCreateVacancyForm(String starterUserId, UUID recruiterUserId, String title, String description,
                                                         Object requiredSkillsRaw, Object screeningThresholdRaw) {
        UserEntity recruiter = resolveRecruiterForVacancyCommand(starterUserId, recruiterUserId);
        String normalizedTitle = normalizeRequiredText(title, "Vacancy title", 255);
        String normalizedDescription = normalizeOptionalText(description, "Vacancy description", 10_000);
        List<String> skills = parseRequiredSkills(requiredSkillsRaw);
        int threshold = requiredScreeningThreshold(screeningThresholdRaw);
        return Map.of(
                "formValidated", true,
                "formErrorMessage", "",
                "recruiterUserId", recruiter.getId(),
                "title", normalizedTitle,
                "description", normalizedDescription,
                "requiredSkills", skills,
                "screeningThreshold", threshold
        );
    }

    @Transactional
    public Map<String, Object> createVacancyFromCamundaForm(String starterUserId, UUID recruiterUserId, String title, String description,
                                                            Object requiredSkillsRaw, Object screeningThresholdRaw,
                                                            String processInstanceId) {
        UserEntity recruiter = resolveRecruiterForVacancyCommand(starterUserId, recruiterUserId);
        String normalizedTitle = normalizeRequiredText(title, "Vacancy title", 255);
        String normalizedDescription = normalizeOptionalText(description, "Vacancy description", 10_000);
        List<String> skills = parseRequiredSkills(requiredSkillsRaw);
        int threshold = requiredScreeningThreshold(screeningThresholdRaw);

        VacancyEntity vacancy = vacancyRepository.save(VacancyEntity.builder()
                .recruiterUser(recruiter)
                .title(normalizedTitle)
                .description(normalizedDescription)
                .status(VacancyStatus.ACTIVE)
                .requiredSkills(skills)
                .screeningThreshold(threshold)
                .camundaProcessInstanceId(processInstanceId)
                .build());
        vacancyHistoryService.record(vacancy, null, VacancyStatus.ACTIVE, recruiter);

        String businessKey = "vacancy:" + vacancy.getId();
        camundaRestClient.updateProcessInstanceBusinessKey(processInstanceId, businessKey);

        return Map.of(
                "vacancyCreated", true,
                "vacancyId", vacancy.getId(),
                "recruiterUserId", recruiter.getId(),
                "title", vacancy.getTitle(),
                "vacancyTitle", vacancy.getTitle(),
                "description", vacancy.getDescription() == null ? "" : vacancy.getDescription(),
                "requiredSkills", vacancy.getRequiredSkills(),
                "screeningThreshold", vacancy.getScreeningThreshold(),
                "status", vacancy.getStatus().name(),
                "vacancyBusinessKey", businessKey
        );
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateCloseVacancyForm(UUID vacancyId, String action, String reason) {
        if (action == null || action.isBlank()) {
            throw new CamundaFormValidationException("Vacancy action is required");
        }
        if (!"CLOSE".equals(action)) {
            throw new CamundaFormValidationException("Vacancy action must be CLOSE");
        }
        if (reason == null || reason.isBlank()) {
            throw new CamundaFormValidationException("Close reason is required");
        }
        if (reason.length() > 5_000) {
            throw new CamundaFormValidationException("Close reason must be at most 5000 characters");
        }
        vacancyRepository.findById(vacancyId).orElseThrow(() -> new CamundaFormValidationException("Vacancy not found: " + vacancyId));
        return Map.of("formValidated", true, "formErrorMessage", "");
    }

    @Transactional
    public Map<String, Object> markVacancyClosed(UUID vacancyId) {
        VacancyEntity vacancy = vacancyRepository.findByIdForUpdate(vacancyId)
                .orElseThrow(() -> new IllegalArgumentException("Vacancy not found: " + vacancyId));
        VacancyStatus oldVacancyStatus = vacancy.getStatus();
        if (oldVacancyStatus != VacancyStatus.CLOSED) {
            vacancy.setStatus(VacancyStatus.CLOSED);
            vacancyHistoryService.record(vacancy, oldVacancyStatus, VacancyStatus.CLOSED, vacancy.getRecruiterUser());
        }
        return Map.of("vacancyClosed", true, "oldVacancyStatus", oldVacancyStatus.name(), "status", vacancy.getStatus().name());
    }

    @Transactional
    public Map<String, Object> cancelActiveInterviewsForVacancy(UUID vacancyId, String reason) {
        List<ApplicationEntity> applications = applicationRepository.findByVacancyIdAndStatusIn(vacancyId, ACTIVE_APPLICATION_STATUSES);
        int cancelled = 0;
        for (ApplicationEntity application : applications) {
            if (interviewService.findActiveByApplicationId(application.getId()).isPresent()) {
                InterviewEntity interview = interviewService.findActiveByApplicationId(application.getId()).get();
                interview.setStatus(InterviewStatus.CANCELLED);
                interview.setCancelReason(reason);
                interview.setCancelledAt(Instant.now());
                cancelled++;
            }
        }
        return Map.of("activeInterviewsCancelled", cancelled);
    }

    @Transactional
    public Map<String, Object> releaseScheduleSlotsForClosedVacancy(UUID vacancyId) {
        List<ApplicationEntity> applications = applicationRepository.findByVacancyIdAndStatusIn(vacancyId, ACTIVE_APPLICATION_STATUSES);
        int released = 0;
        for (ApplicationEntity application : applications) {
            var maybeInterview = interviewService.findActiveByApplicationId(application.getId());
            if (maybeInterview.isPresent()) {
                scheduleService.releaseForInterview(maybeInterview.get());
                released++;
            }
        }
        return Map.of("scheduleSlotsReleased", released);
    }

    @Transactional
    public Map<String, Object> closeActiveApplicationsForVacancy(UUID vacancyId, String reason) {
        VacancyEntity vacancy = vacancyRepository.findByIdForUpdate(vacancyId)
                .orElseThrow(() -> new IllegalArgumentException("Vacancy not found: " + vacancyId));
        List<ApplicationEntity> applications = applicationRepository.findByVacancyIdAndStatusIn(vacancyId, ACTIVE_APPLICATION_STATUSES);
        int closedCount = 0;
        Instant now = Instant.now();
        for (ApplicationEntity application : applications) {
            ApplicationStatus oldStatus = application.getStatus();
            if (oldStatus == ApplicationStatus.CLOSED_BY_VACANCY) {
                continue;
            }
            application.setStatus(ApplicationStatus.CLOSED_BY_VACANCY);
            application.setClosedAt(now);
            application.setInvitationText(null);
            application.setInvitationSentAt(null);
            application.setInvitationExpiresAt(null);
            application.setResponseReceivedAt(null);
            application.setRecruiterComment(reason);
            historyService.record(application, oldStatus, ApplicationStatus.CLOSED_BY_VACANCY, vacancy.getRecruiterUser());
            closedCount++;
        }
        return Map.of("closedApplicationsCount", closedCount, "status", vacancy.getStatus().name());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> recordVacancyClosedHistory(UUID vacancyId) {
        VacancyEntity vacancy = vacancyRepository.findByIdForUpdate(vacancyId)
                .orElseThrow(() -> new IllegalArgumentException("Vacancy not found: " + vacancyId));
        return Map.of("vacancyHistoryRecorded", true, "status", vacancy.getStatus().name());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateAdminResetForm(UUID interviewId, UUID adminUserId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new CamundaFormValidationException("Reset reason is required");
        }
        if (reason.length() > 5_000) {
            throw new CamundaFormValidationException("Reset reason must be at most 5000 characters");
        }
        interviewService.getByIdForUpdate(interviewId);
        userRepository.findById(adminUserId).orElseThrow(() -> new CamundaFormValidationException("Admin user not found: " + adminUserId));
        return Map.of("formValidated", true, "formErrorMessage", "");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateInterviewCanBeReset(UUID interviewId, UUID adminUserId) {
        InterviewEntity interview = interviewService.getByIdForUpdate(interviewId);
        if (interview.getStatus() != InterviewStatus.SCHEDULED && interview.getStatus() != InterviewStatus.CANCELLED) {
            throw new IllegalArgumentException("Interview is not active: " + interviewId);
        }
        return Map.of("interviewCanBeReset", true, "applicationId", interview.getApplication().getId());
    }

    @Transactional
    public Map<String, Object> cancelInterviewByAdmin(UUID interviewId, String reason) {
        InterviewEntity interview = interviewService.getByIdForUpdate(interviewId);
        if (interview.getStatus() != InterviewStatus.CANCELLED) {
            interviewService.cancel(interview, reason);
        }
        return Map.of("interviewCancelled", true, "applicationId", interview.getApplication().getId());
    }

    @Transactional
    public Map<String, Object> releaseAdminResetSlot(UUID interviewId) {
        InterviewEntity interview = interviewService.getByIdForUpdate(interviewId);
        scheduleService.releaseForInterview(interview);
        return Map.of("slotReleased", true, "applicationId", interview.getApplication().getId());
    }

    @Transactional
    public Map<String, Object> returnApplicationToReview(UUID interviewId, UUID adminUserId, String reason) {
        InterviewEntity interview = interviewService.getByIdForUpdate(interviewId);
        ApplicationEntity application = interview.getApplication();
        ApplicationStatus oldStatus = application.getStatus();
        application.setStatus(ApplicationStatus.ON_RECRUITER_REVIEW);
        application.setInvitationText(null);
        application.setInvitationSentAt(null);
        application.setInvitationExpiresAt(null);
        application.setResponseReceivedAt(null);
        application.setRecruiterComment(reason);
        applicationRepository.save(application);
        return Map.of("applicationReturnedToReview", true, "applicationId", application.getId(),
                "oldApplicationStatus", oldStatus.name(), "applicationStatus", application.getStatus().name());
    }

    @Transactional
    public Map<String, Object> recordAdminResetHistory(UUID interviewId, UUID adminUserId) {
        InterviewEntity interview = interviewService.getByIdForUpdate(interviewId);
        UserEntity adminUser = userRepository.findById(adminUserId)
                .orElseThrow(() -> new IllegalArgumentException("Admin user not found: " + adminUserId));
        ApplicationEntity application = interview.getApplication();
        historyService.record(application, ApplicationStatus.INVITATION_RESPONDED, ApplicationStatus.ON_RECRUITER_REVIEW, adminUser);
        return Map.of("adminResetHistoryRecorded", true, "applicationId", application.getId(),
                "applicationStatus", application.getStatus().name());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateVacancyStatusUpdate(UUID vacancyId, UUID recruiterUserId, String starterUserId, String requestedStatus) {
        VacancyEntity vacancy = vacancyRepository.findByIdForUpdate(vacancyId)
                .orElseThrow(() -> new CamundaFormValidationException("Vacancy not found: " + vacancyId));
        UserEntity recruiter = resolveRecruiterForVacancyCommand(starterUserId, recruiterUserId);
        if (!hasRole(recruiter, "RECRUITER")) {
            throw new CamundaFormValidationException("Only RECRUITER users can update vacancies");
        }
        if (!vacancy.getRecruiterUser().getId().equals(recruiter.getId())) {
            throw new CamundaFormValidationException("Vacancy does not belong to current recruiter");
        }
        parseVacancyStatus(requestedStatus);
        return Map.of("formValidated", true, "formErrorMessage", "", "oldVacancyStatus", vacancy.getStatus().name());
    }

    @Transactional
    public Map<String, Object> applyVacancyStatusUpdate(UUID vacancyId, UUID recruiterUserId, String starterUserId, String requestedStatus) {
        validateVacancyStatusUpdate(vacancyId, recruiterUserId, starterUserId, requestedStatus);
        VacancyStatus newStatus = parseVacancyStatus(requestedStatus);
        VacancyEntity vacancy = vacancyRepository.findByIdForUpdate(vacancyId)
                .orElseThrow(() -> new IllegalArgumentException("Vacancy not found: " + vacancyId));
        VacancyStatus oldStatus = vacancy.getStatus();
        if (oldStatus != newStatus) {
            vacancy.setStatus(newStatus);
            UserEntity recruiter = resolveRecruiterForVacancyCommand(starterUserId, recruiterUserId);
            vacancyHistoryService.record(vacancy, oldStatus, newStatus, recruiter);
        }
        return Map.of(
                "vacancyStatusUpdated", true,
                "vacancyId", vacancy.getId(),
                "oldVacancyStatus", oldStatus.name(),
                "status", vacancy.getStatus().name()
        );
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateRecruiterCancelInterview(UUID interviewId, UUID recruiterUserId, String starterUserId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new CamundaFormValidationException("Cancel reason is required");
        }
        if (reason.length() > 5_000) {
            throw new CamundaFormValidationException("Cancel reason must be at most 5000 characters");
        }
        InterviewEntity interview = interviewService.getByIdForUpdate(interviewId);
        UserEntity recruiter = resolveRecruiterForVacancyCommand(starterUserId, recruiterUserId);
        if (interview.getStatus() != InterviewStatus.SCHEDULED) {
            throw new CamundaFormValidationException("Interview is not active");
        }
        if (!interview.getRecruiterUser().getId().equals(recruiter.getId())) {
            throw new CamundaFormValidationException("Interview does not belong to current recruiter");
        }
        return Map.of(
                "formValidated", true,
                "formErrorMessage", "",
                "applicationId", interview.getApplication().getId()
        );
    }

    @Transactional
    public Map<String, Object> cancelInterviewByRecruiter(UUID interviewId, UUID recruiterUserId, String starterUserId, String reason) {
        validateRecruiterCancelInterview(interviewId, recruiterUserId, starterUserId, reason);
        InterviewEntity interview = interviewService.getByIdForUpdate(interviewId);
        interviewService.cancel(interview, reason);
        return Map.of(
                "interviewCancelled", true,
                "interviewId", interview.getId(),
                "applicationId", interview.getApplication().getId(),
                "status", interview.getStatus().name()
        );
    }

    @Transactional
    public Map<String, Object> releaseRecruiterCancelSlot(UUID interviewId) {
        InterviewEntity interview = interviewService.getByIdForUpdate(interviewId);
        scheduleService.releaseForInterview(interview);
        return Map.of("slotReleased", true, "applicationId", interview.getApplication().getId());
    }

    @Transactional
    public Map<String, Object> returnCancelApplicationToReview(UUID interviewId, UUID recruiterUserId, String starterUserId, String reason) {
        InterviewEntity interview = interviewService.getByIdForUpdate(interviewId);
        ApplicationEntity application = interview.getApplication();
        ApplicationStatus oldStatus = application.getStatus();
        application.setStatus(ApplicationStatus.ON_RECRUITER_REVIEW);
        application.setInvitationText(null);
        application.setInvitationSentAt(null);
        application.setInvitationExpiresAt(null);
        application.setResponseReceivedAt(null);
        application.setRecruiterComment(reason);
        applicationRepository.save(application);
        UserEntity recruiter = resolveRecruiterForVacancyCommand(starterUserId, recruiterUserId);
        historyService.record(application, oldStatus, ApplicationStatus.ON_RECRUITER_REVIEW, recruiter);
        return Map.of("applicationReturnedToReview", true, "applicationId", application.getId(),
                "oldApplicationStatus", oldStatus.name(), "applicationStatus", application.getStatus().name());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> recordRecruiterCancelHistory(UUID interviewId, UUID recruiterUserId, String starterUserId) {
        InterviewEntity interview = interviewService.getByIdForUpdate(interviewId);
        resolveRecruiterForVacancyCommand(starterUserId, recruiterUserId);
        return Map.of("recruiterCancelHistoryRecorded", true, "applicationId", interview.getApplication().getId());
    }

    @Transactional
    public Map<String, Object> notifyRecruiterCancelParticipants(UUID applicationId, String reason) {
        ApplicationEntity application = getApplication(applicationId);
        notificationService.create(application.getCandidateUser(), application,
                NotificationType.INTERVIEW_CANCELLED, "Interview was cancelled: " + reason);
        return Map.of("recruiterCancelNotificationSent", true, "applicationId", application.getId(),
                "status", application.getStatus().name());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> loadCandidateVacancyList(String starterUserId) {
        resolveUserFromCamundaStarter(starterUserId, "CANDIDATE");
        List<Map<String, Object>> vacancies = vacancyRepository.findAll().stream()
                .filter(vacancy -> vacancy.getStatus() == VacancyStatus.ACTIVE)
                .map(vacancy -> Map.<String, Object>of(
                        "id", vacancy.getId(),
                        "title", vacancy.getTitle(),
                        "description", vacancy.getDescription() == null ? "" : vacancy.getDescription(),
                        "requiredSkills", vacancy.getRequiredSkills(),
                        "screeningThreshold", vacancy.getScreeningThreshold(),
                        "status", vacancy.getStatus().name()
                ))
                .toList();
        return uiPayload("Активные вакансии", vacancies);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> loadCandidateApplicationList(String starterUserId) {
        UserEntity candidate = resolveUserFromCamundaStarter(starterUserId, "CANDIDATE");
        List<Map<String, Object>> applications = applicationRepository.findByCandidateUserId(candidate.getId()).stream()
                .map(application -> Map.<String, Object>of(
                        "applicationId", application.getId(),
                        "vacancyId", application.getVacancy().getId(),
                        "vacancyTitle", application.getVacancy().getTitle(),
                        "status", application.getStatus().toExternalStatus(),
                        "createdAt", application.getCreatedAt()
                ))
                .toList();
        return uiPayload("Мои отклики", applications);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> loadCandidateApplicationView(String starterUserId, String applicationIdText) {
        UserEntity candidate = resolveUserFromCamundaStarter(starterUserId, "CANDIDATE");
        ApplicationEntity application = getApplication(parseUuidText(applicationIdText, "applicationId"));
        if (!application.getCandidateUser().getId().equals(candidate.getId())) {
            throw new CamundaFormValidationException("Not your application");
        }
        return uiPayload("Отклик кандидата", applicationSummary(application));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> loadRecruiterVacancyList(String starterUserId) {
        UserEntity recruiter = resolveUserFromCamundaStarter(starterUserId, "RECRUITER");
        List<Map<String, Object>> vacancies = vacancyRepository.findByRecruiterUserId(recruiter.getId()).stream()
                .map(vacancy -> Map.<String, Object>of(
                        "id", vacancy.getId(),
                        "title", vacancy.getTitle(),
                        "status", vacancy.getStatus().name(),
                        "requiredSkills", vacancy.getRequiredSkills(),
                        "screeningThreshold", vacancy.getScreeningThreshold()
                ))
                .toList();
        return uiPayload("Вакансии рекрутера", vacancies);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> loadRecruiterApplicationList(String starterUserId) {
        UserEntity recruiter = resolveUserFromCamundaStarter(starterUserId, "RECRUITER");
        List<Map<String, Object>> applications = applicationRepository.findByRecruiterUserId(recruiter.getId()).stream()
                .map(this::applicationSummary)
                .toList();
        return uiPayload("Отклики по моим вакансиям", applications);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> loadRecruiterApplicationView(String starterUserId, String applicationIdText) {
        UserEntity recruiter = resolveUserFromCamundaStarter(starterUserId, "RECRUITER");
        ApplicationEntity application = getApplication(parseUuidText(applicationIdText, "applicationId"));
        if (!application.getVacancy().getRecruiterUser().getId().equals(recruiter.getId())) {
            throw new CamundaFormValidationException("Application does not belong to your vacancy");
        }
        return uiPayload("Отклик для рекрутера", applicationSummary(application));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> loadRecruiterSchedule(String starterUserId, Object weekOffsetRaw) {
        UserEntity recruiter = resolveUserFromCamundaStarter(starterUserId, "RECRUITER");
        int weekOffset = parseWeekOffset(weekOffsetRaw);
        return uiPayload("Расписание рекрутера", scheduleService.getRecruiterWeekSchedule(recruiter, weekOffset));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> loadNotificationList(String starterUserId) {
        UserEntity user = resolveUserFromCamundaStarter(starterUserId, null);
        return uiPayload("Уведомления", notificationService.getNotificationsForUser(user.getId()));
    }

    @Transactional
    public Map<String, Object> runTimeoutReview(String starterUserId) {
        resolveUserFromCamundaStarter(starterUserId, "ADMIN");
        int closedCount = timeoutService.runCloseExpired();
        return uiPayload("Ручная проверка таймаутов", Map.of("closedCount", closedCount));
    }


    public Instant requiredScheduledAt(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            throw new CamundaFormValidationException("Interview date/time is required");
        }
        String raw = String.valueOf(value);
        try {
            return Instant.parse(raw);
        } catch (RuntimeException instantParseException) {
            try {
                return OffsetDateTime.parse(raw).toInstant();
            } catch (RuntimeException offsetParseException) {
                throw new CamundaFormValidationException("Interview date/time must be a valid ISO-8601 timestamp");
            }
        }
    }

    public int requiredDurationMinutes(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            throw new CamundaFormValidationException("Duration is required");
        }
        try {
            if (value instanceof Number number) {
                return number.intValue();
            }
            return Integer.parseInt(String.valueOf(value));
        } catch (RuntimeException e) {
            throw new CamundaFormValidationException("Duration must be an integer number of minutes");
        }
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


    private UserEntity resolveRecruiterFromCamundaStarter(String starterUserId) {
        return resolveUserFromCamundaStarter(starterUserId, "RECRUITER");
    }

    private UserEntity resolveRecruiterForVacancyCommand(String starterUserId, UUID recruiterUserId) {
        if (recruiterUserId != null) {
            UserEntity recruiter = userRepository.findById(recruiterUserId)
                    .orElseThrow(() -> new CamundaFormValidationException("Recruiter user not found: " + recruiterUserId));
            if (!hasRole(recruiter, "RECRUITER")) {
                throw new CamundaFormValidationException("Only RECRUITER users can create vacancies");
            }
            if (!recruiter.isEnabled()) {
                throw new CamundaFormValidationException("User is disabled: " + recruiterUserId);
            }
            return recruiter;
        }
        return resolveRecruiterFromCamundaStarter(starterUserId);
    }

    private UserEntity resolveUserFromCamundaStarter(String starterUserId, String requiredRole) {
        if (starterUserId == null || starterUserId.isBlank()) {
            throw new CamundaFormValidationException("Camunda process starter is not available. Start the process as a synced application user.");
        }
        String normalizedStarter = starterUserId.trim().toLowerCase(java.util.Locale.ROOT);
        UserEntity user = userRepository.findWithRolesByEmail(normalizedStarter)
                .orElseGet(() -> userRepository.findAll().stream()
                        .filter(candidate -> normalizedStarter.equals(CamundaIdentitySyncService.camundaUserId(candidate)))
                        .findFirst()
                        .orElseThrow(() -> new CamundaFormValidationException("Application user is not synced for Camunda user: " + starterUserId)));
        if (requiredRole != null && !hasRole(user, requiredRole)) {
            throw new CamundaFormValidationException("Only " + requiredRole + " users can run this Camunda process");
        }
        if (!user.isEnabled()) {
            throw new CamundaFormValidationException("User is disabled: " + starterUserId);
        }
        return user;
    }

    private boolean hasRole(UserEntity user, String roleCode) {
        return user.getRoles().stream().anyMatch(role -> roleCode.equalsIgnoreCase(role.getCode()));
    }

    private String normalizeRequiredText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new CamundaFormValidationException(fieldName + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new CamundaFormValidationException(fieldName + " must be at most " + maxLength + " characters");
        }
        return normalized;
    }

    private String normalizeOptionalText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new CamundaFormValidationException(fieldName + " must be at most " + maxLength + " characters");
        }
        return normalized;
    }

    private List<String> parseRequiredSkills(Object raw) {
        java.util.List<String> result = new java.util.ArrayList<>();
        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    result.add(String.valueOf(item).trim());
                }
            }
        } else if (raw != null) {
            String rawText = String.valueOf(raw).trim();
            if (rawText.startsWith("[") && rawText.endsWith("]")) {
                rawText = rawText.substring(1, rawText.length() - 1);
            }
            for (String item : rawText.split(",")) {
                if (!item.isBlank()) {
                    result.add(item.trim());
                }
            }
        }
        if (result.isEmpty()) {
            throw new CamundaFormValidationException("At least one required skill is required");
        }
        if (result.size() > 50) {
            throw new CamundaFormValidationException("Required skills list must contain at most 50 items");
        }
        for (String skill : result) {
            if (skill.length() > 100) {
                throw new CamundaFormValidationException("Each skill must be at most 100 characters");
            }
        }
        return java.util.List.copyOf(result);
    }

    private int requiredScreeningThreshold(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            throw new CamundaFormValidationException("Screening threshold is required");
        }
        int value;
        try {
            if (raw instanceof Number number) {
                value = number.intValue();
            } else {
                value = Integer.parseInt(String.valueOf(raw));
            }
        } catch (NumberFormatException e) {
            throw new CamundaFormValidationException("Screening threshold must be a number");
        }
        if (value < 0 || value > 100) {
            throw new CamundaFormValidationException("Screening threshold must be between 0 and 100");
        }
        return value;
    }

    private VacancyStatus parseVacancyStatus(String requestedStatus) {
        if (requestedStatus == null || requestedStatus.isBlank()) {
            throw new CamundaFormValidationException("Vacancy status is required");
        }
        try {
            return VacancyStatus.valueOf(requestedStatus);
        } catch (IllegalArgumentException e) {
            throw new CamundaFormValidationException("Unsupported vacancy status: " + requestedStatus);
        }
    }

    private UUID parseUuidText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new CamundaFormValidationException(fieldName + " is required");
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw new CamundaFormValidationException(fieldName + " must be a valid UUID");
        }
    }

    private int parseWeekOffset(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return 0;
        }
        try {
            int value = raw instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(raw));
            if (value < -52 || value > 52) {
                throw new CamundaFormValidationException("weekOffset must be between -52 and 52");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new CamundaFormValidationException("weekOffset must be an integer");
        }
    }

    private Map<String, Object> applicationSummary(ApplicationEntity application) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applicationId", application.getId());
        result.put("vacancyId", application.getVacancy().getId());
        result.put("vacancyTitle", application.getVacancy().getTitle());
        result.put("candidateId", application.getCandidateUser().getId());
        result.put("candidateEmail", application.getCandidateUser().getEmail());
        result.put("status", application.getStatus().name());
        result.put("resumeText", application.getResumeText());
        result.put("coverLetter", application.getCoverLetter() == null ? "" : application.getCoverLetter());
        result.put("invitationText", application.getInvitationText() == null ? "" : application.getInvitationText());
        result.put("invitationExpiresAt", application.getInvitationExpiresAt());
        result.put("createdAt", application.getCreatedAt());
        result.put("updatedAt", application.getUpdatedAt());
        interviewService.findActiveByApplicationId(application.getId()).ifPresent(interview -> {
            result.put("interviewId", interview.getId());
            result.put("interviewStatus", interview.getStatus().name());
            result.put("scheduledAt", interview.getScheduledAt());
            result.put("durationMinutes", interview.getDurationMinutes());
        });
        return result;
    }

    private Map<String, Object> uiPayload(String title, Object payload) {
        return Map.of(
                "uiTitle", title,
                "uiPayload", toCamundaUiJson(payload),
                "loadedAt", Instant.now().toString()
        );
    }

    private String toCamundaUiJson(Object payload) {
        try {
            return truncateUiPayload(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            return truncateUiPayload(String.valueOf(payload));
        }
    }

    private String truncateUiPayload(String value) {
        if (value == null || value.length() <= UI_PAYLOAD_MAX_LENGTH) {
            return value;
        }
        return value.substring(0, UI_PAYLOAD_MAX_LENGTH) + "\n... truncated for Camunda Tasklist form ...";
    }


    private ApplicationEntity getApplication(UUID applicationId) {
        return applicationRepository.findDetailedById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Application not found: " + applicationId));
    }
}
