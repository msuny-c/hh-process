package ru.itmo.hhprocess.service;

import lombok.RequiredArgsConstructor;
import ru.itmo.hhprocess.dto.recruiter.*;
import ru.itmo.hhprocess.entity.*;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.enums.ErrorCode;
import ru.itmo.hhprocess.enums.NotificationType;
import ru.itmo.hhprocess.exception.ApiException;
import ru.itmo.hhprocess.mapper.ApplicationMapper;
import ru.itmo.hhprocess.repository.ApplicationRepository;
import ru.itmo.hhprocess.repository.ScreeningResultRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RecruiterDecisionService {

    private static final long INVITATION_TTL_HOURS = 48;

    private final ApplicationRepository applicationRepository;
    private final ScreeningResultRepository screeningResultRepository;
    private final VacancyService vacancyService;
    private final HistoryService historyService;
    private final NotificationService notificationService;
    private final ApplicationMapper applicationMapper;

    @Transactional(readOnly = true)
    public List<RecruiterApplicationResponse> getApplications(ApplicationStatus status, UUID vacancyId) {
        RecruiterEntity recruiter = vacancyService.getRecruiterForCurrentUser();
        List<ApplicationEntity> applications;

        if (vacancyId != null) {
            VacancyEntity vacancy = vacancyService.findById(vacancyId);
            if (!vacancy.getRecruiter().getId().equals(recruiter.getId())) {
                throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.AUTH_ACCESS_DENIED, "Not your vacancy");
            }
            applications = status != null
                    ? applicationRepository.findByRecruiterIdAndVacancyIdAndStatus(recruiter.getId(), vacancyId, status)
                    : applicationRepository.findByRecruiterIdAndVacancyId(recruiter.getId(), vacancyId);
        } else if (status != null) {
            applications = applicationRepository.findByRecruiterIdAndStatus(recruiter.getId(), status);
        } else {
            applications = applicationRepository.findByRecruiterId(recruiter.getId());
        }

        if (applications.isEmpty()) {
            return List.of();
        }

        Map<UUID, ScreeningResultEntity> screeningMap = screeningResultRepository
                .findByApplicationIdIn(applications.stream().map(ApplicationEntity::getId).toList())
                .stream()
                .collect(Collectors.toMap(sr -> sr.getApplication().getId(), Function.identity()));

        return applications.stream()
                .map(a -> applicationMapper.toRecruiterResponse(a, screeningMap.get(a.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public RecruiterApplicationResponse getApplication(UUID applicationId) {
        ApplicationEntity application = findAndCheckOwnership(applicationId, vacancyService.getRecruiterForCurrentUser());
        return applicationMapper.toRecruiterResponse(
                application,
                screeningResultRepository.findByApplicationId(application.getId()).orElse(null)
        );
    }

    @Transactional
    public RejectResponse reject(UUID applicationId, RejectRequest request) {
        RecruiterEntity recruiter = vacancyService.getRecruiterForCurrentUser();
        ApplicationEntity application = findAndCheckOwnership(applicationId, recruiter);
        ensureStatus(application, ApplicationStatus.ON_RECRUITER_REVIEW);

        application.setStatus(ApplicationStatus.REJECTED_BY_RECRUITER);
        application.setRecruiterComment(request.getComment());
        application.setClosedAt(Instant.now());

        historyService.record(application,
                ApplicationStatus.ON_RECRUITER_REVIEW,
                ApplicationStatus.REJECTED_BY_RECRUITER,
                "RECRUITER_REJECTED", request.getComment(), recruiter.getUser());

        notificationService.create(application.getCandidate().getUser(), application,
                NotificationType.APPLICATION_REJECTED, "Your application has been rejected");

        return RejectResponse.builder()
                .applicationId(application.getId())
                .status(ApplicationStatus.REJECTED_BY_RECRUITER.toExternalStatus())
                .build();
    }

    @Transactional
    public InviteResponse invite(UUID applicationId, InviteRequest request) {
        RecruiterEntity recruiter = vacancyService.getRecruiterForCurrentUser();
        ApplicationEntity application = findAndCheckOwnership(applicationId, recruiter);
        ensureStatus(application, ApplicationStatus.ON_RECRUITER_REVIEW);

        Instant now = Instant.now();
        Instant expiresAt = now.plus(INVITATION_TTL_HOURS, ChronoUnit.HOURS);

        application.setStatus(ApplicationStatus.INVITED);
        application.setInvitationText(request.getMessage());
        application.setInvitationSentAt(now);
        application.setInvitationExpiresAt(expiresAt);

        historyService.record(application,
                ApplicationStatus.ON_RECRUITER_REVIEW,
                ApplicationStatus.INVITED,
                "INVITATION_SENT", request.getMessage(), recruiter.getUser());

        notificationService.create(application.getCandidate().getUser(), application,
                NotificationType.INVITATION, "You have been invited: " + request.getMessage());

        return InviteResponse.builder()
                .applicationId(application.getId())
                .status(ApplicationStatus.INVITED.toExternalStatus())
                .expiresAt(expiresAt)
                .build();
    }

    private ApplicationEntity findAndCheckOwnership(UUID applicationId, RecruiterEntity recruiter) {
        ApplicationEntity application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCode.APPLICATION_NOT_FOUND, "Application not found"));

        if (!application.getVacancy().getRecruiter().getId().equals(recruiter.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.AUTH_ACCESS_DENIED,
                    "Application does not belong to your vacancy");
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
