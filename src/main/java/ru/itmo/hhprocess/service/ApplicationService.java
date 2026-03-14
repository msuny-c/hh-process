package ru.itmo.hhprocess.service;

import lombok.RequiredArgsConstructor;
import ru.itmo.hhprocess.dto.candidate.CandidateApplicationResponse;
import ru.itmo.hhprocess.dto.candidate.CreateApplicationRequest;
import ru.itmo.hhprocess.dto.candidate.CreateApplicationResponse;
import ru.itmo.hhprocess.entity.*;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.enums.ErrorCode;
import ru.itmo.hhprocess.enums.NotificationType;
import ru.itmo.hhprocess.enums.VacancyStatus;
import ru.itmo.hhprocess.exception.ApiException;
import ru.itmo.hhprocess.mapper.ApplicationMapper;
import ru.itmo.hhprocess.repository.ApplicationRepository;
import ru.itmo.hhprocess.repository.CandidateRepository;
import ru.itmo.hhprocess.security.JwtPrincipal;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ApplicationService {

    private static final List<ApplicationStatus> TERMINAL_STATUSES = List.of(
            ApplicationStatus.SCREENING_FAILED,
            ApplicationStatus.REJECTED_BY_RECRUITER,
            ApplicationStatus.INVITATION_RESPONDED,
            ApplicationStatus.CLOSED_BY_TIMEOUT
    );

    private final ApplicationRepository applicationRepository;
    private final CandidateRepository candidateRepository;
    private final VacancyService vacancyService;
    private final ScreeningService screeningService;
    private final HistoryService historyService;
    private final NotificationService notificationService;
    private final AuthService authService;
    private final ApplicationMapper applicationMapper;

    @Transactional
    public CreateApplicationResponse create(UUID vacancyId, CreateApplicationRequest request) {
        JwtPrincipal principal = authService.getCurrentPrincipal();
        CandidateEntity candidate = findCandidateForUser(principal.userId());
        VacancyEntity vacancy = vacancyService.findByIdForUpdate(vacancyId);

        ensureVacancyActive(vacancy);
        ensureNoActiveApplication(candidate.getId(), vacancy.getId());

        ApplicationEntity application = saveNewApplication(vacancy, candidate, request);
        ScreeningResultEntity screeningResult = screeningService.performScreening(application);

        return screeningResult.isPassed()
                ? handleScreeningPassed(application, vacancy)
                : handleScreeningFailed(application, candidate.getUser());
    }

    @Transactional(readOnly = true)
    public List<CandidateApplicationResponse> getMyApplications() {
        JwtPrincipal principal = authService.getCurrentPrincipal();
        CandidateEntity candidate = findCandidateForUser(principal.userId());

        return applicationRepository.findByCandidateId(candidate.getId()).stream()
                .map(applicationMapper::toCandidateResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public CandidateApplicationResponse getApplicationForCandidate(UUID applicationId) {
        JwtPrincipal principal = authService.getCurrentPrincipal();
        CandidateEntity candidate = findCandidateForUser(principal.userId());

        ApplicationEntity application = findById(applicationId);
        if (!application.getCandidate().getId().equals(candidate.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.AUTH_ACCESS_DENIED,
                    "Not your application");
        }
        return applicationMapper.toCandidateResponse(application);
    }

    @Transactional(readOnly = true)
    public ApplicationEntity findById(UUID id) {
        return applicationRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCode.APPLICATION_NOT_FOUND, "Application not found"));
    }

    private CandidateEntity findCandidateForUser(UUID userId) {
        return candidateRepository.findByUserId(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN,
                        ErrorCode.AUTH_ACCESS_DENIED, "Candidate profile not found"));
    }

    private void ensureVacancyActive(VacancyEntity vacancy) {
        if (vacancy.getStatus() != VacancyStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.VACANCY_NOT_ACTIVE,
                    "Vacancy is not active");
        }
    }

    private void ensureNoActiveApplication(UUID candidateId, UUID vacancyId) {
        if (applicationRepository.existsByCandidateIdAndVacancyIdAndStatusNotIn(
                candidateId, vacancyId, TERMINAL_STATUSES)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.APPLICATION_ALREADY_EXISTS,
                    "You already have an active application for this vacancy");
        }
    }

    private ApplicationEntity saveNewApplication(VacancyEntity vacancy, CandidateEntity candidate,
                                                  CreateApplicationRequest request) {
        ApplicationEntity application = ApplicationEntity.builder()
                .vacancy(vacancy)
                .candidate(candidate)
                .resumeText(request.getResumeText())
                .coverLetter(request.getCoverLetter())
                .status(ApplicationStatus.SCREENING_IN_PROGRESS)
                .build();
        application = applicationRepository.save(application);

        historyService.record(application, null,
                ApplicationStatus.SCREENING_IN_PROGRESS,
                null, "Application created", null);

        return application;
    }

    private CreateApplicationResponse handleScreeningFailed(ApplicationEntity application, UserEntity candidateUser) {
        application.setStatus(ApplicationStatus.SCREENING_FAILED);
        application.setClosedAt(Instant.now());
        applicationRepository.save(application);

        historyService.record(application,
                ApplicationStatus.SCREENING_IN_PROGRESS,
                ApplicationStatus.SCREENING_FAILED,
                "SCREENING_FAILED", "Auto-screening not passed", null);

        notificationService.create(candidateUser, application,
                NotificationType.SCREENING_RESULT, "Your application has been rejected");

        return CreateApplicationResponse.builder()
                .applicationId(application.getId())
                .status(ApplicationStatus.SCREENING_FAILED.toExternalStatus())
                .message("Application rejected")
                .build();
    }

    private CreateApplicationResponse handleScreeningPassed(ApplicationEntity application, VacancyEntity vacancy) {
        application.setStatus(ApplicationStatus.ON_RECRUITER_REVIEW);
        applicationRepository.save(application);

        historyService.record(application,
                ApplicationStatus.SCREENING_IN_PROGRESS,
                ApplicationStatus.ON_RECRUITER_REVIEW,
                "SCREENING_PASSED", "Auto-screening passed", null);

        UserEntity recruiterUser = vacancy.getRecruiter().getUser();
        notificationService.create(recruiterUser, application,
                NotificationType.NEW_APPLICATION, "New application received for vacancy: " + vacancy.getTitle());

        return CreateApplicationResponse.builder()
                .applicationId(application.getId())
                .status(ApplicationStatus.ON_RECRUITER_REVIEW.toExternalStatus())
                .message("Application submitted")
                .build();
    }
}
