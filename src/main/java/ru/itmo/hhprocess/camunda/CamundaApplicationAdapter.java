package ru.itmo.hhprocess.camunda;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.InterviewEntity;
import ru.itmo.hhprocess.entity.InvitationResponseEntity;
import ru.itmo.hhprocess.entity.ScreeningResultEntity;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.entity.VacancyEntity;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.enums.ErrorCode;
import ru.itmo.hhprocess.enums.NotificationType;
import ru.itmo.hhprocess.enums.ResponseType;
import ru.itmo.hhprocess.enums.VacancyStatus;
import ru.itmo.hhprocess.exception.ApiException;
import ru.itmo.hhprocess.repository.ApplicationRepository;
import ru.itmo.hhprocess.repository.InvitationResponseRepository;
import ru.itmo.hhprocess.repository.UserRepository;
import ru.itmo.hhprocess.repository.VacancyRepository;
import ru.itmo.hhprocess.service.HistoryService;
import ru.itmo.hhprocess.service.InterviewService;
import ru.itmo.hhprocess.service.NotificationService;
import ru.itmo.hhprocess.service.ScheduleService;
import ru.itmo.hhprocess.service.ScreeningService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CamundaApplicationAdapter {

    private static final long INVITATION_TTL_HOURS = 48;

    private final ApplicationRepository applicationRepository;
    private final VacancyRepository vacancyRepository;
    private final UserRepository userRepository;
    private final InvitationResponseRepository invitationResponseRepository;
    private final ScreeningService screeningService;
    private final HistoryService historyService;
    private final NotificationService notificationService;
    private final InterviewService interviewService;
    private final ScheduleService scheduleService;
    private final CamundaRestClient camundaRestClient;
    private final CamundaFormValidator formValidator;
    private final CamundaFormValueParser formValueParser;
    private final CamundaUserResolver userResolver;

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

    @Transactional(readOnly = true)
    public Map<String, Object> prepareAutoScreen(UUID applicationId) {
        ApplicationEntity application = getApplication(applicationId);
        var input = screeningService.prepareScreeningInput(application);
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("screeningRequiredSkills", String.join(", ", input.requiredSkills()));
        variables.put("screeningMatchedSkills", String.join(", ", input.matchedSkills()));
        variables.put("screeningMatchedCount", input.matchedSkills().size());
        variables.put("screeningTotalSkills", input.requiredSkills().size());
        variables.put("screeningThreshold", input.threshold());
        variables.put("screeningScore", input.score());
        variables.put("screeningScoreDelta", input.score() - input.threshold());
        variables.put("autoScreeningPrepared", true);
        return variables;
    }

    @Transactional
    public Map<String, Object> saveAutoScreenDecision(UUID applicationId, boolean screeningPassed, int screeningScore) {
        ApplicationEntity application = getApplication(applicationId);
        ApplicationStatus oldStatus = application.getStatus();
        var input = screeningService.prepareScreeningInput(application);
        ScreeningResultEntity screeningResult = screeningService.saveScreeningDecision(application, input, screeningPassed);

        if (oldStatus == ApplicationStatus.SCREENING_IN_PROGRESS) {
            if (screeningPassed) {
                application.setStatus(ApplicationStatus.ON_RECRUITER_REVIEW);
                historyService.record(application, oldStatus, ApplicationStatus.ON_RECRUITER_REVIEW, null);
            } else {
                application.setStatus(ApplicationStatus.SCREENING_FAILED);
                application.setClosedAt(Instant.now());
                historyService.record(application, oldStatus, ApplicationStatus.SCREENING_FAILED, null);
            }
            applicationRepository.save(application);
        }

        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("screeningPassed", screeningResult.isPassed());
        variables.put("status", application.getStatus().name());
        variables.put("screeningScore", screeningScore);
        variables.put("autoScreeningCompleted", true);
        variables.put("autoScreeningDecisionOwner", "Camunda DMN hhAutoScreening");
        return variables;
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

        cancelActiveInterview(application, comment);

        application.setStatus(ApplicationStatus.REJECTED_BY_RECRUITER);
        application.setRecruiterComment(comment);
        application.setClosedAt(Instant.now());
        applicationRepository.save(application);

        historyService.record(application, oldStatus, ApplicationStatus.REJECTED_BY_RECRUITER,
                application.getVacancy().getRecruiterUser());

        return Map.of("rejectionPersisted", true, "status", application.getStatus().name());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateInvitationForm(UUID applicationId, String message, Instant scheduledAt,
                                                       int durationMinutes) {
        formValidator.requiredText(message, "Invitation message", 5_000);
        formValidator.requireNonNull(scheduledAt, "Interview date/time is required");
        if (scheduledAt.isBefore(Instant.now())) {
            throw new CamundaFormValidationException("Interview date/time must be in the future");
        }
        formValidator.integerRange(durationMinutes, "Duration", 15, 480);

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
    public Map<String, Object> closeByTimeout(UUID applicationId) {
        ApplicationEntity application = getApplication(applicationId);
        if (application.getStatus() == ApplicationStatus.CLOSED_BY_TIMEOUT) {
            return Map.of("timeoutClosed", true, "status", application.getStatus().name(), "idempotent", true);
        }
        if (application.getStatus() != ApplicationStatus.INVITED) {
            return Map.of("timeoutClosed", false, "status", application.getStatus().name());
        }

        cancelActiveInterview(application, "Invitation expired");

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

    @Transactional(readOnly = true)
    public Map<String, Object> validateApplyToVacancyForm(UUID applicationId, UUID vacancyId, UUID candidateUserId,
                                                          String starterUserId, String resumeText, String coverLetter) {
        formValidator.requiredText(resumeText, "Resume text", 50_000);
        if (resumeText.trim().length() < 20) {
            throw new CamundaFormValidationException("Resume text length must be between 20 and 50000 characters");
        }
        formValidator.maxLength(coverLetter, "Cover letter", 10_000);
        if (applicationId != null) {
            ApplicationEntity application = getApplication(applicationId);
            UserEntity candidate = application.getCandidateUser();
            UserEntity recruiter = application.getVacancy().getRecruiterUser();
            return Map.of(
                    "formValidated", true,
                    "formErrorMessage", "",
                    "applicationId", applicationId,
                    "candidateUserId", candidate.getId(),
                    "candidateCamundaUserId", CamundaIdentitySyncService.camundaUserId(candidate),
                    "recruiterUserId", recruiter.getId(),
                    "recruiterCamundaUserId", CamundaIdentitySyncService.camundaUserId(recruiter),
                    "vacancyTitle", application.getVacancy().getTitle()
            );
        }

        VacancyEntity vacancy = vacancyRepository.findByIdForUpdate(vacancyId)
                .orElseThrow(() -> new CamundaFormValidationException("Vacancy not found: " + vacancyId));
        if (vacancy.getStatus() != VacancyStatus.ACTIVE) {
            throw new CamundaFormValidationException("Vacancy is not active");
        }
        UserEntity candidate = candidateUserId == null
                ? userResolver.resolveUserFromCamundaStarter(starterUserId, "CANDIDATE")
                : userRepository.findById(candidateUserId)
                .orElseThrow(() -> new CamundaFormValidationException("Candidate user not found: " + candidateUserId));
        if (!userResolver.hasRole(candidate, "CANDIDATE")) {
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
                "candidateCamundaUserId", CamundaIdentitySyncService.camundaUserId(candidate),
                "recruiterUserId", vacancy.getRecruiterUser().getId(),
                "recruiterCamundaUserId", CamundaIdentitySyncService.camundaUserId(vacancy.getRecruiterUser()),
                "vacancyTitle", vacancy.getTitle()
        );
    }

    @Transactional
    public Map<String, Object> createApplicationFromCamundaForm(UUID existingApplicationId, UUID vacancyId, UUID candidateUserId,
                                                                String starterUserId, String resumeText, String coverLetter,
                                                                String processInstanceId) {
        if (existingApplicationId != null) {
            ApplicationEntity application = getApplication(existingApplicationId);
            UserEntity candidate = application.getCandidateUser();
            UserEntity recruiter = application.getVacancy().getRecruiterUser();
            if (application.getCamundaProcessInstanceId() == null || application.getCamundaProcessInstanceId().isBlank()) {
                application.setCamundaProcessInstanceId(processInstanceId);
                applicationRepository.save(application);
            }
            String businessKey = "application:" + application.getId();
            camundaRestClient.updateProcessInstanceBusinessKey(processInstanceId, businessKey);
            return Map.of(
                    "applicationCreated", true,
                    "applicationId", application.getId(),
                    "candidateUserId", candidate.getId(),
                    "candidateCamundaUserId", CamundaIdentitySyncService.camundaUserId(candidate),
                    "recruiterUserId", recruiter.getId(),
                    "recruiterCamundaUserId", CamundaIdentitySyncService.camundaUserId(recruiter),
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
                ? userResolver.resolveUserFromCamundaStarter(starterUserId, "CANDIDATE")
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
                "candidateCamundaUserId", CamundaIdentitySyncService.camundaUserId(candidate),
                "recruiterUserId", vacancy.getRecruiterUser().getId(),
                "recruiterCamundaUserId", CamundaIdentitySyncService.camundaUserId(vacancy.getRecruiterUser()),
                "vacancyTitle", vacancy.getTitle(),
                "status", application.getStatus().name(),
                "applicationBusinessKey", businessKey
        );
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateRecruiterDecisionForm(UUID applicationId, String decision, String comment) {
        formValidator.requiredChoice(decision, "Recruiter decision", Set.of("INVITE", "REJECT", "VACANCY_CLOSED"));
        if ("REJECT".equals(decision)) {
            formValidator.requireNotBlank(comment, "Rejection comment is required");
        }
        formValidator.maxLength(comment, "Recruiter comment", 5_000);
        ApplicationEntity application = getApplication(applicationId);
        if (application.getStatus() != ApplicationStatus.ON_RECRUITER_REVIEW) {
            throw new CamundaFormValidationException("Application is not waiting for recruiter decision: " + application.getStatus());
        }
        return Map.of("formValidated", true, "formErrorMessage", "");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateCandidateResponseForm(UUID applicationId, String responseType, String message) {
        formValueParser.requiredResponseType(responseType);
        formValidator.maxLength(message, "Candidate response message", 5_000);
        ApplicationEntity application = getApplication(applicationId);
        if (application.getStatus() != ApplicationStatus.INVITED) {
            throw new CamundaFormValidationException("Application is not waiting for candidate response: " + application.getStatus());
        }
        return Map.of("formValidated", true, "formErrorMessage", "");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateRejectionAllowed(UUID applicationId, String comment) {
        formValidator.requiredText(comment, "Rejection comment", 5_000);
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
        cancelActiveInterview(application, comment);
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

    @Transactional
    public Map<String, Object> rollbackApplicationTransaction(UUID applicationId, String reason) {
        ApplicationEntity application = getApplication(applicationId);
        ApplicationStatus oldStatus = application.getStatus();
        cancelActiveInterview(application, reason);
        if (oldStatus == ApplicationStatus.INVITED || oldStatus == ApplicationStatus.INVITATION_RESPONDED) {
            application.setStatus(ApplicationStatus.ON_RECRUITER_REVIEW);
            clearInvitationState(application, reason);
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

    private ApplicationEntity getApplication(UUID applicationId) {
        return applicationRepository.findDetailedById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Application not found: " + applicationId));
    }

    private void cancelActiveInterview(ApplicationEntity application, String reason) {
        interviewService.findActiveByApplicationId(application.getId()).ifPresent(interview -> {
            interviewService.cancel(interview, reason);
            scheduleService.releaseForInterview(interview);
        });
    }

    private void clearInvitationState(ApplicationEntity application, String reason) {
        application.setInvitationText(null);
        application.setInvitationSentAt(null);
        application.setInvitationExpiresAt(null);
        application.setResponseReceivedAt(null);
        application.setRecruiterComment(reason);
    }
}
