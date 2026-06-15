package ru.itmo.hhprocess.service;

import lombok.RequiredArgsConstructor;
import ru.itmo.hhprocess.exception.CamundaFormValidationException;
import ru.itmo.hhprocess.utils.CamundaFormValidator;
import ru.itmo.hhprocess.camunda.CamundaIdentitySyncService;
import ru.itmo.hhprocess.camunda.CamundaRestClient;
import ru.itmo.hhprocess.config.CamundaProperties;
import ru.itmo.hhprocess.dto.candidate.CandidateApplicationResponse;
import ru.itmo.hhprocess.dto.candidate.CreateApplicationRequest;
import ru.itmo.hhprocess.dto.candidate.CreateApplicationResponse;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.entity.VacancyEntity;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.enums.ErrorCode;
import ru.itmo.hhprocess.enums.NotificationType;
import ru.itmo.hhprocess.enums.VacancyStatus;
import ru.itmo.hhprocess.exception.ApiException;
import ru.itmo.hhprocess.mapper.ApplicationMapper;
import ru.itmo.hhprocess.repository.ApplicationRepository;
import ru.itmo.hhprocess.repository.UserRepository;
import ru.itmo.hhprocess.repository.VacancyRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ApplicationService {

        private final ApplicationRepository applicationRepository;
        private final VacancyRepository vacancyRepository;
        private final UserRepository userRepository;
        private final VacancyService vacancyService;
        private final HistoryService historyService;
        private final NotificationService notificationService;
        private final AuthService authService;
        private final ApplicationMapper applicationMapper;
        private final InterviewService interviewService;
        private final ScheduleService scheduleService;
        private final CamundaRestClient camundaRestClient;
        private final CamundaProperties camundaProperties;
        private final CamundaFormValidator formValidator;
        private final CamundaIdentitySyncService camundaIdentitySyncService;

        public record ApplyToVacancyValidationContext(UUID applicationId, UUID vacancyId, UUID candidateUserId,
                                                       String candidateCamundaUserId, UUID recruiterUserId,
                                                       String vacancyTitle) {
        }

        public record CreateApplicationFromProcessResult(ApplicationEntity application, VacancyEntity vacancy,
                                                         UserEntity candidate, String businessKey, boolean idempotent) {
        }

        public record RejectionResult(ApplicationEntity application, boolean idempotent, boolean terminal) {
        }

        public record ApplicationRejectedResult(ApplicationEntity application, String oldStatus, boolean idempotent) {
        }

        public record TimeoutCloseResult(ApplicationEntity application, boolean closed, boolean idempotent) {
        }

        public record RollbackResult(ApplicationEntity application, ApplicationStatus oldStatus) {
        }

        @Transactional
        public CreateApplicationResponse create(UUID vacancyId, CreateApplicationRequest request) {
                UserEntity candidateUser = authService.getCurrentUser();
                VacancyEntity vacancy = vacancyService.findByIdForUpdate(vacancyId);

                if (vacancy.getStatus() != VacancyStatus.ACTIVE) {
                        throw new ApiException(HttpStatus.CONFLICT, ErrorCode.VACANCY_NOT_ACTIVE,
                                        "Vacancy is not active");
                }

                if (applicationRepository.existsByCandidateUserIdAndVacancyId(candidateUser.getId(), vacancyId)) {
                        throw new ApiException(HttpStatus.CONFLICT, ErrorCode.APPLICATION_ALREADY_EXISTS,
                                        "You already have an application for this vacancy");
                }

                Map<String, Object> variables = new LinkedHashMap<>();
                variables.put("vacancyId", vacancy.getId());
                variables.put("candidateUserId", candidateUser.getId());
                variables.put("candidateCamundaUserId", CamundaIdentitySyncService.camundaUserId(candidateUser));
                variables.put("recruiterUserId", vacancy.getRecruiterUser().getId());
                variables.put("vacancyTitle", vacancy.getTitle());
                variables.put("resumeText", request.getResumeText() == null ? "" : request.getResumeText());
                variables.put("coverLetter", request.getCoverLetter() == null ? "" : request.getCoverLetter());
                variables.put("status", ApplicationStatus.SCREENING_IN_PROGRESS.name());
                variables.put("restAutoSubmit", true);
                String processInstanceId = camundaRestClient.startProcessByKey(
                                camundaProperties.getApplicationProcessKey(),
                                "application-request:" + UUID.randomUUID(),
                                variables)
                                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                                                ErrorCode.INVALID_APPLICATION_STATE,
                                                "Camunda application process was not started"));
                ApplicationEntity application = waitForApplicationCreated(processInstanceId);

                return CreateApplicationResponse.builder()
                                .applicationId(application.getId())
                                .status(ApplicationStatus.SCREENING_IN_PROGRESS.toExternalStatus())
                                .message("Application submitted")
                                .build();
        }

        @Transactional(readOnly = true)
        public List<CandidateApplicationResponse> getMyApplications() {
                UserEntity candidateUser = authService.getCurrentUser();

                return applicationRepository.findByCandidateUserId(candidateUser.getId()).stream()
                                .map(a -> applicationMapper.toCandidateResponse(a, interviewService.findActiveByApplicationId(a.getId()).orElse(null)))
                                .toList();
        }

        @Transactional(readOnly = true)
        public CandidateApplicationResponse getApplicationForCandidate(UUID applicationId) {
                UserEntity candidateUser = authService.getCurrentUser();

                ApplicationEntity application = findById(applicationId);
                if (!application.getCandidateUser().getId().equals(candidateUser.getId())) {
                        throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.AUTH_ACCESS_DENIED,
                                        "Not your application");
                }
                return applicationMapper.toCandidateResponse(application, interviewService.findActiveByApplicationId(application.getId()).orElse(null));
        }

        @Transactional(readOnly = true)
        public ApplicationEntity findById(UUID id) {
                return applicationRepository.findById(id)
                                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                                ErrorCode.APPLICATION_NOT_FOUND, "Application not found"));
        }

        @Transactional(readOnly = true)
        public ApplyToVacancyValidationContext validateApplyToVacancyForm(UUID applicationId, UUID vacancyId,
                                                                          UUID candidateUserId, String starterUserId,
                                                                          String resumeText, String coverLetter) {
                formValidator.requiredText(resumeText, "Resume text", 50_000);
                if (resumeText.trim().length() < 20) {
                        throw new CamundaFormValidationException(
                                "Resume text length must be between 20 and 50000 characters");
                }
                formValidator.maxLength(coverLetter, "Cover letter", 10_000);
                if (applicationId != null) {
                        getApplication(applicationId);
                        return new ApplyToVacancyValidationContext(applicationId, null, null, null, null, null);
                }

                VacancyEntity vacancy = vacancyRepository.findByIdForUpdate(vacancyId)
                        .orElseThrow(() -> new CamundaFormValidationException("Vacancy not found: " + vacancyId));
                if (vacancy.getStatus() != VacancyStatus.ACTIVE) {
                        throw new CamundaFormValidationException("Vacancy is not active");
                }
                UserEntity candidate = candidateUserId == null
                        ? camundaIdentitySyncService.resolveUserFromCamundaStarter(starterUserId, "CANDIDATE")
                        : userRepository.findById(candidateUserId)
                        .orElseThrow(() -> new CamundaFormValidationException(
                                "Candidate user not found: " + candidateUserId));
                if (!camundaIdentitySyncService.hasRole(candidate, "CANDIDATE")) {
                        throw new CamundaFormValidationException("Only CANDIDATE users can apply to vacancies");
                }
                if (applicationRepository.existsByCandidateUserIdAndVacancyId(candidate.getId(), vacancy.getId())) {
                        throw new CamundaFormValidationException("You already have an application for this vacancy");
                }
                return new ApplyToVacancyValidationContext(
                        null,
                        vacancy.getId(),
                        candidate.getId(),
                        camundaIdentitySyncService.camundaUserId(candidate),
                        vacancy.getRecruiterUser().getId(),
                        vacancy.getTitle()
                );
        }

        @Transactional
        public CreateApplicationFromProcessResult createApplicationFromCamundaForm(UUID existingApplicationId,
                                                                                   UUID vacancyId, UUID candidateUserId,
                                                                                   String starterUserId,
                                                                                   String resumeText, String coverLetter,
                                                                                   String processInstanceId) {
                if (existingApplicationId != null) {
                        ApplicationEntity application = getApplication(existingApplicationId);
                        if (application.getCamundaProcessInstanceId() == null
                                || application.getCamundaProcessInstanceId().isBlank()) {
                                application.setCamundaProcessInstanceId(processInstanceId);
                                applicationRepository.save(application);
                        }
                        String businessKey = "application:" + application.getId();
                        camundaRestClient.updateProcessInstanceBusinessKey(processInstanceId, businessKey);
                        return new CreateApplicationFromProcessResult(application, null, null, businessKey, true);
                }
                if (processInstanceId != null && !processInstanceId.isBlank()) {
                        var existing = applicationRepository.findByCamundaProcessInstanceId(processInstanceId);
                        if (existing.isPresent()) {
                                ApplicationEntity application = existing.get();
                                return new CreateApplicationFromProcessResult(
                                        application, null, null, null, true);
                        }
                }
                validateApplyToVacancyForm(null, vacancyId, candidateUserId, starterUserId, resumeText, coverLetter);
                VacancyEntity vacancy = vacancyRepository.findByIdForUpdate(vacancyId)
                        .orElseThrow(() -> new CamundaFormValidationException("Vacancy not found: " + vacancyId));
                UserEntity candidate = candidateUserId == null
                        ? camundaIdentitySyncService.resolveUserFromCamundaStarter(starterUserId, "CANDIDATE")
                        : userRepository.findById(candidateUserId)
                        .orElseThrow(() -> new CamundaFormValidationException(
                                "Candidate user not found: " + candidateUserId));

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

                return new CreateApplicationFromProcessResult(application, vacancy, candidate, businessKey, false);
        }

        @Transactional(readOnly = true)
        public void validateRecruiterDecisionForm(UUID applicationId, String decision, String comment) {
                formValidator.requiredChoice(decision, "Recruiter decision", Set.of("INVITE", "REJECT", "VACANCY_CLOSED"));
                if ("REJECT".equals(decision)) {
                        formValidator.requireNotBlank(comment, "Rejection comment is required");
                }
                formValidator.maxLength(comment, "Recruiter comment", 5_000);
                ApplicationEntity application = getApplication(applicationId);
                if (application.getStatus() != ApplicationStatus.ON_RECRUITER_REVIEW) {
                        throw new CamundaFormValidationException(
                                "Application is not waiting for recruiter decision: " + application.getStatus());
                }
        }

        @Transactional
        public RejectionResult rejectApplication(UUID applicationId, String comment) {
                ApplicationEntity application = getApplication(applicationId);
                ApplicationStatus oldStatus = application.getStatus();
                if (oldStatus == ApplicationStatus.REJECTED_BY_RECRUITER) {
                        return new RejectionResult(application, true, false);
                }
                if (oldStatus.isTerminal()) {
                        return new RejectionResult(application, false, true);
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

                return new RejectionResult(application, false, false);
        }

        @Transactional
        public ApplicationEntity notifyApplicationRejected(UUID applicationId) {
                ApplicationEntity application = getApplication(applicationId);
                notificationService.createIfAbsent(application.getCandidateUser(), application,
                        NotificationType.APPLICATION_REJECTED, "Your application has been rejected");
                return application;
        }

        @Transactional(readOnly = true)
        public String validateRejectionAllowed(UUID applicationId, String comment) {
                formValidator.requiredText(comment, "Rejection comment", 5_000);
                ApplicationEntity application = getApplication(applicationId);
                if (application.getStatus() != ApplicationStatus.ON_RECRUITER_REVIEW
                        && application.getStatus() != ApplicationStatus.INVITED
                        && application.getStatus() != ApplicationStatus.INVITATION_RESPONDED) {
                        throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_APPLICATION_STATE,
                                "Application cannot be rejected from state: " + application.getStatus());
                }
                return application.getStatus().name();
        }

        @Transactional
        public void cancelRejectionInterviewIfAny(UUID applicationId, String comment) {
                ApplicationEntity application = getApplication(applicationId);
                interviewService.findActiveByApplicationId(application.getId()).ifPresent(interview -> {
                        interviewService.cancel(interview, comment);
                        scheduleService.releaseForInterview(interview);
                });
        }

        @Transactional
        public ApplicationRejectedResult markApplicationRejected(UUID applicationId, String comment) {
                ApplicationEntity application = getApplication(applicationId);
                ApplicationStatus oldStatus = application.getStatus();
                if (oldStatus == ApplicationStatus.REJECTED_BY_RECRUITER) {
                        return new ApplicationRejectedResult(application, oldStatus.name(), true);
                }
                application.setStatus(ApplicationStatus.REJECTED_BY_RECRUITER);
                application.setRecruiterComment(comment);
                application.setClosedAt(Instant.now());
                applicationRepository.save(application);
                return new ApplicationRejectedResult(application, oldStatus.name(), false);
        }

        @Transactional
        public ApplicationEntity recordRejectionHistory(UUID applicationId) {
                ApplicationEntity application = getApplication(applicationId);
                historyService.record(application, ApplicationStatus.ON_RECRUITER_REVIEW,
                        ApplicationStatus.REJECTED_BY_RECRUITER, application.getVacancy().getRecruiterUser());
                return application;
        }

        @Transactional
        public TimeoutCloseResult closeByTimeout(UUID applicationId) {
                ApplicationEntity application = getApplication(applicationId);
                if (application.getStatus() == ApplicationStatus.CLOSED_BY_TIMEOUT) {
                        return new TimeoutCloseResult(application, true, true);
                }
                if (application.getStatus() != ApplicationStatus.INVITED) {
                        return new TimeoutCloseResult(application, false, false);
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

                return new TimeoutCloseResult(application, true, false);
        }

        @Transactional
        public RollbackResult rollbackApplicationTransaction(UUID applicationId, String reason) {
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
                return new RollbackResult(application, oldStatus);
        }

        private ApplicationEntity getApplication(UUID applicationId) {
                return applicationRepository.findDetailedById(applicationId)
                        .orElseThrow(() -> new IllegalArgumentException("Application not found: " + applicationId));
        }

        private ApplicationEntity waitForApplicationCreated(String processInstanceId) {
                for (int attempt = 0; attempt < 60; attempt++) {
                        var application = applicationRepository.findByCamundaProcessInstanceId(processInstanceId);
                        if (application.isPresent()) {
                                return application.get();
                        }
                        sleep();
                }
                throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_APPLICATION_STATE,
                                "Camunda process did not create application in time");
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
