package com.example.hhprocess.service;

import com.example.hhprocess.dto.*;
import com.example.hhprocess.entity.JobApplication;
import com.example.hhprocess.entity.Vacancy;
import com.example.hhprocess.enums.ApplicationStatus;
import com.example.hhprocess.enums.NotificationType;
import com.example.hhprocess.enums.VacancyStatus;
import com.example.hhprocess.exception.BadRequestException;
import com.example.hhprocess.exception.NotFoundException;
import com.example.hhprocess.mapper.ApplicationMapper;
import com.example.hhprocess.repository.ApplicationStatusHistoryRepository;
import com.example.hhprocess.repository.JobApplicationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
public class ApplicationWorkflowService {
    private static final Set<ApplicationStatus> TERMINAL_STATUSES = Set.of(
            ApplicationStatus.AUTO_REJECTED,
            ApplicationStatus.HR_REJECTED,
            ApplicationStatus.IN_RESERVE,
            ApplicationStatus.ACCEPTED,
            ApplicationStatus.EXPIRED
    );

    private final JobApplicationRepository applicationRepository;
    private final ApplicationStatusHistoryRepository historyRepository;
    private final VacancyService vacancyService;
    private final CandidateService candidateService;
    private final HistoryService historyService;
    private final NotificationService notificationService;
    private final ApplicationMapper applicationMapper;

    public ApplicationResponse create(CreateApplicationRequest request) {
        Vacancy vacancy = vacancyService.findEntityById(request.getVacancyId());
        if (vacancy.getStatus() != VacancyStatus.OPEN) {
            throw new BadRequestException("Cannot apply to a closed vacancy");
        }

        JobApplication application = JobApplication.builder()
                .vacancy(vacancy)
                .candidate(candidateService.findEntityById(request.getCandidateId()))
                .coverLetter(request.getCoverLetter())
                .status(ApplicationStatus.NEW)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        application = applicationRepository.save(application);
        historyService.save(application, null, ApplicationStatus.NEW, "SYSTEM", "Application created");
        return applicationMapper.toResponse(application);
    }

    @Transactional(readOnly = true)
    public ApplicationResponse getById(Long id) {
        return applicationMapper.toResponse(findEntityById(id));
    }

    @Transactional(readOnly = true)
    public List<ApplicationResponse> getAll(Long vacancyId, Long candidateId, ApplicationStatus status) {
        List<JobApplication> applications;
        if (vacancyId != null && status != null) {
            applications = applicationRepository.findByVacancy_IdAndStatus(vacancyId, status);
        } else if (vacancyId != null) {
            applications = applicationRepository.findByVacancy_Id(vacancyId);
        } else if (candidateId != null) {
            applications = applicationRepository.findByCandidate_Id(candidateId);
        } else if (status != null) {
            applications = applicationRepository.findByStatus(status);
        } else {
            applications = applicationRepository.findAll();
        }
        return applications.stream().map(applicationMapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<ApplicationHistoryResponse> getHistory(Long applicationId) {
        findEntityById(applicationId);
        return historyRepository.findByApplication_IdOrderByChangedAtAsc(applicationId)
                .stream()
                .map(applicationMapper::toHistoryResponse)
                .toList();
    }

    public ApplicationResponse validate(Long id) {
        JobApplication application = findEntityById(id);
        ensureNotTerminal(application);
        ensureState(application, Set.of(ApplicationStatus.NEW, ApplicationStatus.NEEDS_REVIEW));

        boolean missingCoverLetter = application.getCoverLetter() == null || application.getCoverLetter().isBlank();
        boolean missingResume = application.getCandidate().getResumeText() == null || application.getCandidate().getResumeText().isBlank();

        if (missingCoverLetter || missingResume) {
            return updateStatus(application, ApplicationStatus.NEEDS_REVIEW, "SYSTEM", "Need manual review due to incomplete data", null, null);
        }
        return updateStatus(application, ApplicationStatus.UNDER_REVIEW, "SYSTEM", "Application moved to HR review", null, null);
    }

    public ApplicationResponse autoReject(Long id, DecisionRequest request) {
        JobApplication application = findEntityById(id);
        ensureState(application, Set.of(ApplicationStatus.UNDER_REVIEW));
        return updateStatus(application, ApplicationStatus.AUTO_REJECTED, "SYSTEM", request.getComment(), NotificationType.AUTO_REJECTION,
                "Your application was automatically rejected: " + request.getComment());
    }

    public ApplicationResponse rejectByHr(Long id, DecisionRequest request) {
        JobApplication application = findEntityById(id);
        ensureState(application, Set.of(ApplicationStatus.UNDER_REVIEW, ApplicationStatus.NEEDS_REVIEW));
        return updateStatus(application, ApplicationStatus.HR_REJECTED, "HR", request.getComment(), NotificationType.HR_REJECTION,
                "HR decision: application rejected. Reason: " + request.getComment());
    }

    public ApplicationResponse invite(Long id, DecisionRequest request) {
        JobApplication application = findEntityById(id);
        ensureState(application, Set.of(ApplicationStatus.UNDER_REVIEW, ApplicationStatus.NEEDS_REVIEW));
        return updateStatus(application, ApplicationStatus.INVITED, "HR", request.getComment(), NotificationType.INVITATION,
                "You are invited to the next stage. Comment: " + request.getComment());
    }

    public ApplicationResponse reserve(Long id, DecisionRequest request) {
        JobApplication application = findEntityById(id);
        ensureState(application, Set.of(ApplicationStatus.UNDER_REVIEW, ApplicationStatus.NEEDS_REVIEW));
        return updateStatus(application, ApplicationStatus.IN_RESERVE, "HR", request.getComment(), NotificationType.RESERVE,
                "Your application was moved to reserve. Comment: " + request.getComment());
    }

    public ApplicationResponse acceptInvitation(Long id, DecisionRequest request) {
        JobApplication application = findEntityById(id);
        ensureState(application, Set.of(ApplicationStatus.INVITED));
        return updateStatus(application, ApplicationStatus.ACCEPTED, "CANDIDATE", request.getComment(), NotificationType.ACCEPTANCE,
                "Candidate accepted invitation. Comment: " + request.getComment());
    }

    public ApplicationResponse expireInvitation(Long id) {
        JobApplication application = findEntityById(id);
        ensureState(application, Set.of(ApplicationStatus.INVITED));
        return updateStatus(application, ApplicationStatus.EXPIRED, "SYSTEM", "Invitation expired by timeout", NotificationType.EXPIRATION,
                "Invitation expired due to timeout");
    }

    @Transactional(readOnly = true)
    public JobApplication findEntityById(Long id) {
        return applicationRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Application with id=" + id + " not found"));
    }

    private ApplicationResponse updateStatus(JobApplication application,
                                             ApplicationStatus newStatus,
                                             String changedBy,
                                             String comment,
                                             NotificationType notificationType,
                                             String notificationMessage) {
        ApplicationStatus oldStatus = application.getStatus();
        application.setStatus(newStatus);
        application.setUpdatedAt(LocalDateTime.now());
        JobApplication saved = applicationRepository.save(application);
        historyService.save(saved, oldStatus, newStatus, changedBy, comment);
        if (notificationType != null && notificationMessage != null) {
            notificationService.create(saved, notificationType, notificationMessage);
        }
        return applicationMapper.toResponse(saved);
    }

    private void ensureState(JobApplication application, Set<ApplicationStatus> allowed) {
        ensureNotTerminal(application);
        if (!allowed.contains(application.getStatus())) {
            throw new BadRequestException("Invalid application state: " + application.getStatus());
        }
    }

    private void ensureNotTerminal(JobApplication application) {
        if (TERMINAL_STATUSES.contains(application.getStatus())) {
            throw new BadRequestException("Application already finished with status: " + application.getStatus());
        }
    }
}
