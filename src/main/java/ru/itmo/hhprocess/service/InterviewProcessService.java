package ru.itmo.hhprocess.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.dto.recruiter.*;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.InterviewEntity;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.enums.ErrorCode;
import ru.itmo.hhprocess.enums.NotificationType;
import ru.itmo.hhprocess.exception.ApiException;
import ru.itmo.hhprocess.messaging.producer.NotificationRequestPublisher;
import ru.itmo.hhprocess.repository.ApplicationRepository;
import ru.itmo.hhprocess.entity.RecruiterScheduleSlotEntity;

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
    private final NotificationRequestPublisher notificationRequestPublisher;

    @Transactional
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
        Instant expiresAt = now.plus(INVITATION_TTL_HOURS, ChronoUnit.HOURS);

        application.setStatus(ApplicationStatus.INVITED);
        application.setInvitationText(request.getMessage());
        application.setInvitationSentAt(now);
        application.setInvitationExpiresAt(expiresAt);
        applicationRepository.save(application);

        InterviewEntity interview = interviewService.createScheduledInterview(application, recruiterUser, scheduledAt, duration, request.getMessage());
        RecruiterScheduleSlotEntity slot = scheduleService.reserveOnTheFly(recruiterUser, interview, scheduledAt, duration);

        historyService.record(application, ApplicationStatus.ON_RECRUITER_REVIEW, ApplicationStatus.INVITED, recruiterUser);
        notificationRequestPublisher.publishAfterCommit(application.getCandidateUser(), application, NotificationType.INVITATION,
                "You have been invited to an interview: " + request.getMessage());

        return InviteResponse.builder()
                .applicationId(application.getId())
                .status(ApplicationStatus.INVITED.toExternalStatus())
                .expiresAt(expiresAt)
                .interviewId(interview.getId())
                .scheduledAt(interview.getScheduledAt())
                .durationMinutes(interview.getDurationMinutes())
                .scheduleSlotId(slot.getId())
                .build();
    }

    @Transactional
    public RejectResponse reject(UUID applicationId, RejectRequest request) {
        UserEntity recruiterUser = vacancyService.getRecruiterUserForCurrentUser();
        ApplicationEntity application = findAndCheckOwnership(applicationId, recruiterUser);
        ApplicationStatus oldStatus = application.getStatus();
        if (oldStatus.isTerminal()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_APPLICATION_STATE, "Application is already closed");
        }

        interviewService.findActiveByApplicationId(application.getId()).ifPresent(interview -> {
            interviewService.cancel(interview, request.getComment());
            scheduleService.releaseForInterview(interview);
        });

        application.setStatus(ApplicationStatus.REJECTED_BY_RECRUITER);
        application.setRecruiterComment(request.getComment());
        application.setClosedAt(Instant.now());
        applicationRepository.save(application);

        historyService.record(application, oldStatus, ApplicationStatus.REJECTED_BY_RECRUITER, recruiterUser);
        notificationRequestPublisher.publishAfterCommit(application.getCandidateUser(), application, NotificationType.APPLICATION_REJECTED,
                "Your application has been rejected");

        return RejectResponse.builder().applicationId(application.getId()).status(ApplicationStatus.REJECTED_BY_RECRUITER.toExternalStatus()).build();
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
        notificationRequestPublisher.publishAfterCommit(application.getCandidateUser(), application, NotificationType.INTERVIEW_CANCELLED,
                "Interview was cancelled: " + request.getReason());

        return InterviewActionResponse.builder()
                .interviewId(interview.getId())
                .applicationId(application.getId())
                .status("CANCELLED")
                .message("Interview cancelled")
                .build();
    }

    private ApplicationEntity findAndCheckOwnership(UUID applicationId, UserEntity recruiterUser) {
        ApplicationEntity application = applicationRepository.findById(applicationId)
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
}
