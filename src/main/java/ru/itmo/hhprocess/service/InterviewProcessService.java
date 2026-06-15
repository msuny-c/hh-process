package ru.itmo.hhprocess.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.exception.CamundaFormValidationException;
import ru.itmo.hhprocess.utils.CamundaFormValidator;
import ru.itmo.hhprocess.camunda.CamundaIdentitySyncService;
import ru.itmo.hhprocess.camunda.CamundaRestClient;
import ru.itmo.hhprocess.config.CamundaProperties;
import ru.itmo.hhprocess.dto.recruiter.*;
import ru.itmo.hhprocess.dto.admin.ResetInterviewRequest;
import ru.itmo.hhprocess.dto.admin.ResetInterviewResponse;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.InterviewEntity;
import ru.itmo.hhprocess.entity.RecruiterScheduleSlotEntity;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.enums.ErrorCode;
import ru.itmo.hhprocess.enums.InterviewStatus;
import ru.itmo.hhprocess.enums.NotificationType;
import ru.itmo.hhprocess.exception.ApiException;
import ru.itmo.hhprocess.repository.ApplicationRepository;
import ru.itmo.hhprocess.repository.UserRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InterviewProcessService {

    private static final long INVITATION_TTL_HOURS = 48;
    private static final int DEFAULT_DURATION_MINUTES = 60;
    private static final long DEFAULT_DELAY_HOURS = 24;

    private final ApplicationRepository applicationRepository;
    private final VacancyService vacancyService;
    private final InterviewService interviewService;
    private final ScheduleService scheduleService;
    private final HistoryService historyService;
    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final CamundaFormValidator formValidator;
    private final CamundaIdentitySyncService camundaIdentitySyncService;
    private final CamundaRestClient camundaRestClient;
    private final CamundaProperties camundaProperties;
    private final AuthService authService;

    public InviteResponse invite(UUID applicationId, InviteRequest request) {
        UserEntity recruiterUser = vacancyService.getRecruiterUserForCurrentUser();
        ApplicationEntity application = findAndCheckOwnership(applicationId, recruiterUser);
        if (application.getStatus() == ApplicationStatus.INVITED || application.getStatus() == ApplicationStatus.INVITATION_RESPONDED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_APPLICATION_STATE, "Invitation already sent for this application");
        }
        ensureStatus(application, ApplicationStatus.ON_RECRUITER_REVIEW);
        if (interviewService.findActiveByApplicationId(application.getId()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_APPLICATION_STATE, "Active interview already exists");
        }

        Instant now = Instant.now();
        Instant scheduledAt = request.getScheduledAt() != null ? request.getScheduledAt() : now.plus(DEFAULT_DELAY_HOURS, ChronoUnit.HOURS);
        if (scheduledAt.isBefore(now.plus(5, ChronoUnit.MINUTES))) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_APPLICATION_STATE, "Interview must be scheduled in the future");
        }
        int duration = request.getDurationMinutes() != null ? request.getDurationMinutes() : DEFAULT_DURATION_MINUTES;
        scheduleService.ensureAvailable(recruiterUser, scheduledAt, duration);
        Instant expiresAt = now.plus(INVITATION_TTL_HOURS, ChronoUnit.HOURS);

        String businessKey = applicationBusinessKey(application.getId());
        if (!camundaRestClient.hasActiveProcessInstance(camundaProperties.getApplicationProcessKey(), businessKey)) {
            camundaRestClient.startProcessByKey(camundaProperties.getApplicationProcessKey(), businessKey,
                    Map.of("applicationId", application.getId(), "restAutoSubmit", true));
        }
        if (!camundaRestClient.completeFirstTask(businessKey, "RecruiterDecisionTask", Map.of("decision", "INVITE", "decidedAt", now))
                && !camundaRestClient.completeFirstTask(businessKey, "WriteInvitationTask", Map.of(
                "invitationMessage", request.getMessage() == null ? "" : request.getMessage(),
                "scheduledAt", scheduledAt,
                "durationMinutes", duration,
                "invitationExpiresAt", expiresAt))) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_APPLICATION_STATE,
                    "Camunda recruiter invitation task is not active");
        }

        application = waitForApplicationStatus(applicationId, ApplicationStatus.INVITED);
        InterviewEntity interview = waitForActiveInterview(applicationId);
        RecruiterScheduleSlotEntity slot = waitForScheduleSlot(interview);
        return InviteResponse.builder()
                .applicationId(application.getId())
                .status(ApplicationStatus.INVITED.toExternalStatus())
                .expiresAt(application.getInvitationExpiresAt())
                .interviewId(interview.getId())
                .scheduledAt(interview.getScheduledAt())
                .durationMinutes(interview.getDurationMinutes())
                .scheduleSlotId(slot != null ? slot.getId() : null)
                .build();
    }

    public RejectResponse reject(UUID applicationId, RejectRequest request) {
        UserEntity recruiterUser = vacancyService.getRecruiterUserForCurrentUser();
        ApplicationEntity application = findAndCheckOwnership(applicationId, recruiterUser);
        ApplicationStatus oldStatus = application.getStatus();
        if (oldStatus.isTerminal()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_APPLICATION_STATE, "Application is already closed");
        }

        if (!camundaRestClient.completeFirstTask(applicationBusinessKey(application.getId()), "RecruiterDecisionTask",
                Map.of("decision", "REJECT", "recruiterComment", request.getComment() == null ? "" : request.getComment(), "decidedAt", Instant.now()))) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_APPLICATION_STATE,
                    "Camunda recruiter decision task is not active");
        }

        application = waitForApplicationStatus(applicationId, ApplicationStatus.REJECTED_BY_RECRUITER);
        return RejectResponse.builder().applicationId(application.getId()).status(ApplicationStatus.REJECTED_BY_RECRUITER.toExternalStatus()).build();
    }

    public ResetInterviewResponse resetInterviewByAdmin(UUID interviewId, ResetInterviewRequest request) {
        UserEntity adminUser = authService.getCurrentUser();
        InterviewEntity interview = interviewService.getByIdForUpdate(interviewId);
        if (interview.getStatus() != ru.itmo.hhprocess.enums.InterviewStatus.SCHEDULED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_APPLICATION_STATE, "Interview is not active");
        }

        if (camundaRestClient.startProcessByKey(
                camundaProperties.getAdminInterviewResetProcessKey(),
                "admin-reset:" + interviewId + ":" + UUID.randomUUID(),
                Map.of("interviewId", interviewId, "applicationId", interview.getApplication().getId(),
                        "adminUserId", adminUser.getId(), "resetReason", request.getReason() == null ? "" : request.getReason()))
                .isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_APPLICATION_STATE,
                    "Camunda admin reset process was not started");
        }

        interview = waitForInterviewCancelled(interviewId);
        camundaRestClient.correlateMessage("MSG_ADMIN_RESET_DONE", applicationBusinessKey(interview.getApplication().getId()),
                Map.of("responseType", "ADMIN_RESET", "cancelReason", request.getReason() == null ? "" : request.getReason()));
        return ResetInterviewResponse.builder()
                .interviewId(interview.getId())
                .applicationId(interview.getApplication().getId())
                .status("CANCELLED")
                .message("Interview reset")
                .build();
    }

    public InterviewActionResponse cancelInterview(UUID interviewId, CancelInterviewRequest request) {
        UserEntity recruiterUser = vacancyService.getRecruiterUserForCurrentUser();
        InterviewEntity interview = interviewService.getByIdForUpdate(interviewId);
        if (!interview.getRecruiterUser().getId().equals(recruiterUser.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.AUTH_ACCESS_DENIED, "Interview does not belong to your vacancy");
        }
        if (interview.getStatus() != ru.itmo.hhprocess.enums.InterviewStatus.SCHEDULED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_APPLICATION_STATE, "Interview is not active");
        }

        ApplicationEntity application = interview.getApplication();
        if (camundaRestClient.startProcessByKey(
                camundaProperties.getRecruiterInterviewCancelProcessKey(),
                "interview-cancel:" + interviewId + ":" + UUID.randomUUID(),
                Map.of("interviewId", interviewId, "applicationId", application.getId(),
                        "recruiterUserId", recruiterUser.getId(), "cancelReason", request.getReason() == null ? "" : request.getReason()))
                .isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_APPLICATION_STATE,
                    "Camunda recruiter interview cancel process was not started");
        }

        interview = waitForInterviewCancelled(interviewId);
        application = waitForApplicationStatus(application.getId(), ApplicationStatus.ON_RECRUITER_REVIEW);
        camundaRestClient.correlateMessage("MSG_INTERVIEW_CANCELLED", applicationBusinessKey(application.getId()),
                Map.of("responseType", "RECRUITER_CANCEL", "cancelReason", request.getReason() == null ? "" : request.getReason()));

        return InterviewActionResponse.builder()
                .interviewId(interview.getId())
                .applicationId(application.getId())
                .status("CANCELLED")
                .message("Interview cancelled")
                .build();
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
    public Map<String, Object> notifyInvitation(UUID applicationId, String message) {
        ApplicationEntity application = getApplication(applicationId);
        notificationService.createIfAbsent(application.getCandidateUser(), application,
                NotificationType.INVITATION, "You have been invited to an interview: " + message);
        return Map.of("notificationSent", true, "status", application.getStatus().name());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateAdminResetForm(UUID interviewId, UUID adminUserId, String reason) {
        formValidator.requiredText(reason, "Reset reason", 5_000);
        interviewService.getByIdForUpdate(interviewId);
        userRepository.findById(adminUserId)
                .orElseThrow(() -> new CamundaFormValidationException("Admin user not found: " + adminUserId));
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
        historyService.record(application, ApplicationStatus.INVITATION_RESPONDED, ApplicationStatus.ON_RECRUITER_REVIEW,
                adminUser);
        return Map.of("adminResetHistoryRecorded", true, "applicationId", application.getId(),
                "applicationStatus", application.getStatus().name());
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

    @Transactional(readOnly = true)
    public Map<String, Object> validateRecruiterCancelInterview(UUID interviewId, UUID recruiterUserId,
                                                                String starterUserId, String reason) {
        formValidator.requiredText(reason, "Cancel reason", 5_000);
        InterviewEntity interview = interviewService.getByIdForUpdate(interviewId);
        UserEntity recruiter = camundaIdentitySyncService.resolveRecruiterForVacancyCommand(starterUserId, recruiterUserId);
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
    public Map<String, Object> cancelInterviewByRecruiter(UUID interviewId, UUID recruiterUserId, String starterUserId,
                                                        String reason) {
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
    public Map<String, Object> returnCancelApplicationToReview(UUID interviewId, UUID recruiterUserId,
                                                               String starterUserId, String reason) {
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
        UserEntity recruiter = camundaIdentitySyncService.resolveRecruiterForVacancyCommand(starterUserId, recruiterUserId);
        historyService.record(application, oldStatus, ApplicationStatus.ON_RECRUITER_REVIEW, recruiter);
        return Map.of("applicationReturnedToReview", true, "applicationId", application.getId(),
                "oldApplicationStatus", oldStatus.name(), "applicationStatus", application.getStatus().name());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> recordRecruiterCancelHistory(UUID interviewId, UUID recruiterUserId,
                                                            String starterUserId) {
        InterviewEntity interview = interviewService.getByIdForUpdate(interviewId);
        camundaIdentitySyncService.resolveRecruiterForVacancyCommand(starterUserId, recruiterUserId);
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

    private ApplicationEntity getApplication(UUID applicationId) {
        return applicationRepository.findDetailedById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Application not found: " + applicationId));
    }

    private ApplicationEntity findAndCheckOwnership(UUID applicationId, UserEntity recruiterUser) {
        ApplicationEntity application = applicationRepository.findDetailedById(applicationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCode.APPLICATION_NOT_FOUND, "Application not found"));
        if (!application.getVacancy().getRecruiterUser().getId().equals(recruiterUser.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.AUTH_ACCESS_DENIED, "Application does not belong to your vacancy");
        }
        return application;
    }

    private void ensureStatus(ApplicationEntity application, ApplicationStatus expected) {
        if (application.getStatus() != expected) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_APPLICATION_STATE,
                    "Application is not in " + expected + " status");
        }
    }

    private ApplicationEntity waitForApplicationStatus(UUID applicationId, ApplicationStatus expected) {
        for (int attempt = 0; attempt < 60; attempt++) {
            ApplicationEntity application = applicationRepository.findDetailedById(applicationId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCode.APPLICATION_NOT_FOUND, "Application not found"));
            if (application.getStatus() == expected) {
                return application;
            }
            sleep();
        }
        throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_APPLICATION_STATE,
                "Camunda process did not reach " + expected + " in time");
    }

    private InterviewEntity waitForActiveInterview(UUID applicationId) {
        for (int attempt = 0; attempt < 60; attempt++) {
            var interview = interviewService.findActiveByApplicationId(applicationId);
            if (interview.isPresent()) {
                return interview.get();
            }
            sleep();
        }
        throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_APPLICATION_STATE,
                "Camunda process did not create an active interview in time");
    }

    private RecruiterScheduleSlotEntity waitForScheduleSlot(InterviewEntity interview) {
        for (int attempt = 0; attempt < 60; attempt++) {
            RecruiterScheduleSlotEntity slot = scheduleService.findByInterviewId(interview);
            if (slot != null) {
                return slot;
            }
            sleep();
        }
        throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_APPLICATION_STATE,
                "Camunda process did not reserve a schedule slot in time");
    }

    private InterviewEntity waitForInterviewCancelled(UUID interviewId) {
        for (int attempt = 0; attempt < 24; attempt++) {
            InterviewEntity interview = interviewService.getByIdForUpdate(interviewId);
            if (interview.getStatus() == ru.itmo.hhprocess.enums.InterviewStatus.CANCELLED) {
                return interview;
            }
            sleep();
        }
        throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_APPLICATION_STATE,
                "Camunda process did not reset interview in time");
    }

    private static void sleep() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_APPLICATION_STATE,
                    "Interrupted while waiting for Camunda process");
        }
    }

    private static String applicationBusinessKey(UUID applicationId) {
        return "application:" + applicationId;
    }
}
