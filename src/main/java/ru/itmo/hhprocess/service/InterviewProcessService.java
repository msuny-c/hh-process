package ru.itmo.hhprocess.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.dto.recruiter.*;
import ru.itmo.hhprocess.dto.admin.ResetInterviewRequest;
import ru.itmo.hhprocess.dto.admin.ResetInterviewResponse;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.InterviewEntity;
import ru.itmo.hhprocess.entity.RecruiterScheduleSlotEntity;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.enums.ErrorCode;
import ru.itmo.hhprocess.enums.NotificationType;
import ru.itmo.hhprocess.exception.ApiException;
import ru.itmo.hhprocess.repository.ApplicationRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    private final ru.itmo.hhprocess.camunda.CamundaWorkflowFacade camundaWorkflowFacade;
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

        camundaWorkflowFacade.ensureApplicationProcessActive(application);
        if (!camundaWorkflowFacade.recruiterInvited(application, recruiterUser, request.getMessage(), scheduledAt, duration, expiresAt)) {
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

        if (!camundaWorkflowFacade.recruiterRejected(application, recruiterUser, request.getComment())) {
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

        if (!camundaWorkflowFacade.adminResetInterview(interview, adminUser, request.getReason())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_APPLICATION_STATE,
                    "Camunda admin reset process was not started");
        }

        interview = waitForInterviewCancelled(interviewId);
        return ResetInterviewResponse.builder()
                .interviewId(interview.getId())
                .applicationId(interview.getApplication().getId())
                .status("CANCELLED")
                .message("Interview reset")
                .build();
    }

    @Transactional
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
        ApplicationStatus oldStatus = application.getStatus();
        Instant now = Instant.now();

        interview.setStatus(ru.itmo.hhprocess.enums.InterviewStatus.CANCELLED);
        interview.setCancelReason(request.getReason());
        interview.setCancelledAt(now);

        scheduleService.releaseForInterview(interview);

        application.setStatus(ApplicationStatus.ON_RECRUITER_REVIEW);
        application.setInvitationText(null);
        application.setInvitationSentAt(null);
        application.setInvitationExpiresAt(null);
        application.setResponseReceivedAt(null);
        application.setRecruiterComment(request.getReason());

        historyService.record(application, oldStatus, ApplicationStatus.ON_RECRUITER_REVIEW, recruiterUser);
        notificationService.create(application.getCandidateUser(), application, NotificationType.INTERVIEW_CANCELLED,
                "Interview was cancelled: " + request.getReason());

        return InterviewActionResponse.builder()
                .interviewId(interview.getId())
                .applicationId(application.getId())
                .status("CANCELLED")
                .message("Interview cancelled")
                .build();
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
}
