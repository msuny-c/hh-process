package ru.itmo.hhprocess.camunda;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.InterviewEntity;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.enums.InterviewStatus;
import ru.itmo.hhprocess.repository.ApplicationRepository;
import ru.itmo.hhprocess.repository.UserRepository;
import ru.itmo.hhprocess.service.HistoryService;
import ru.itmo.hhprocess.service.InterviewService;
import ru.itmo.hhprocess.service.ScheduleService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CamundaInterviewAdapter {

    private final ApplicationRepository applicationRepository;
    private final UserRepository userRepository;
    private final HistoryService historyService;
    private final InterviewService interviewService;
    private final ScheduleService scheduleService;
    private final CamundaFormValidator formValidator;
    private final CamundaUserResolver userResolver;
    private final CamundaNotificationAdapter notificationAdapter;

    @Transactional
    public Map<String, Object> resetInterviewByAdmin(UUID interviewId, UUID adminUserId, String reason) {
        Map<String, Object> dbResult = resetInterviewByAdminInDb(interviewId, adminUserId, reason);
        UUID applicationId = (UUID) dbResult.get("applicationId");
        var notificationResult = notificationAdapter.notifyAdminInterviewReset(applicationId, reason);
        var result = new LinkedHashMap<String, Object>(dbResult);
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
        clearInvitationState(application, reason);
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

    @Transactional(readOnly = true)
    public Map<String, Object> validateAdminResetForm(UUID interviewId, UUID adminUserId, String reason) {
        formValidator.requiredText(reason, "Reset reason", 5_000);
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
        clearInvitationState(application, reason);
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
    public Map<String, Object> validateRecruiterCancelInterview(UUID interviewId, UUID recruiterUserId, String starterUserId, String reason) {
        formValidator.requiredText(reason, "Cancel reason", 5_000);
        InterviewEntity interview = interviewService.getByIdForUpdate(interviewId);
        UserEntity recruiter = userResolver.resolveRecruiterForVacancyCommand(starterUserId, recruiterUserId);
        if (interview.getStatus() != InterviewStatus.SCHEDULED) {
            throw new CamundaFormValidationException("Interview is not active");
        }
        if (!interview.getRecruiterUser().getId().equals(recruiter.getId())) {
            throw new CamundaFormValidationException("Interview does not belong to current recruiter");
        }
        return Map.of(
                "formValidated", true,
                "formErrorMessage", "",
                "applicationId", interview.getApplication().getId(),
                "recruiterUserId", recruiter.getId(),
                "recruiterCamundaUserId", CamundaIdentitySyncService.camundaUserId(recruiter)
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
        clearInvitationState(application, reason);
        applicationRepository.save(application);
        UserEntity recruiter = userResolver.resolveRecruiterForVacancyCommand(starterUserId, recruiterUserId);
        historyService.record(application, oldStatus, ApplicationStatus.ON_RECRUITER_REVIEW, recruiter);
        return Map.of("applicationReturnedToReview", true, "applicationId", application.getId(),
                "oldApplicationStatus", oldStatus.name(), "applicationStatus", application.getStatus().name());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> recordRecruiterCancelHistory(UUID interviewId, UUID recruiterUserId, String starterUserId) {
        InterviewEntity interview = interviewService.getByIdForUpdate(interviewId);
        userResolver.resolveRecruiterForVacancyCommand(starterUserId, recruiterUserId);
        return Map.of("recruiterCancelHistoryRecorded", true, "applicationId", interview.getApplication().getId());
    }

    private void clearInvitationState(ApplicationEntity application, String reason) {
        application.setInvitationText(null);
        application.setInvitationSentAt(null);
        application.setInvitationExpiresAt(null);
        application.setResponseReceivedAt(null);
        application.setRecruiterComment(reason);
    }
}
