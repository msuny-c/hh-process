package ru.itmo.hhprocess.camunda;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.InterviewEntity;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.entity.VacancyEntity;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.enums.InterviewStatus;
import ru.itmo.hhprocess.enums.VacancyStatus;
import ru.itmo.hhprocess.repository.ApplicationRepository;
import ru.itmo.hhprocess.repository.VacancyRepository;
import ru.itmo.hhprocess.service.HistoryService;
import ru.itmo.hhprocess.service.InterviewService;
import ru.itmo.hhprocess.service.ScheduleService;
import ru.itmo.hhprocess.service.VacancyHistoryService;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CamundaVacancyAdapter {

    private static final List<ApplicationStatus> ACTIVE_APPLICATION_STATUSES = List.of(
            ApplicationStatus.SCREENING_IN_PROGRESS,
            ApplicationStatus.ON_RECRUITER_REVIEW,
            ApplicationStatus.INVITED,
            ApplicationStatus.INVITATION_RESPONDED
    );

    private final ApplicationRepository applicationRepository;
    private final VacancyRepository vacancyRepository;
    private final HistoryService historyService;
    private final VacancyHistoryService vacancyHistoryService;
    private final InterviewService interviewService;
    private final ScheduleService scheduleService;
    private final CamundaRestClient camundaRestClient;
    private final CamundaFormValidator formValidator;
    private final CamundaFormValueParser formValueParser;
    private final CamundaUserResolver userResolver;
    private final CamundaNotificationAdapter notificationAdapter;

    @Transactional
    public Map<String, Object> closeVacancyApplications(UUID vacancyId, String reason) {
        Map<String, Object> dbResult = closeVacancyApplicationsInDb(vacancyId, reason);
        notificationAdapter.notifyVacancyClosedCandidates(vacancyId);
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
            clearInvitationState(application, reason);
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

    @Transactional(readOnly = true)
    public Map<String, Object> prepareStatusTransition(String currentStatus, String action, String requestedStatus) {
        return Map.of(
                "currentStatus", currentStatus == null ? "UNKNOWN" : currentStatus,
                "statusAction", action == null ? "UNKNOWN" : action,
                "requestedStatus", requestedStatus == null ? "" : requestedStatus
        );
    }

    @Transactional(readOnly = true)
    public Map<String, Object> prepareRecruiterDecisionTransition(UUID applicationId, String decision) {
        ApplicationEntity application = getApplication(applicationId);
        String action = switch (decision == null ? "" : decision) {
            case "INVITE" -> "INVITE_APPLICATION";
            case "REJECT" -> "REJECT_APPLICATION";
            case "VACANCY_CLOSED" -> "CLOSE_BY_TIMEOUT";
            default -> "UNKNOWN";
        };
        return prepareStatusTransition(application.getStatus().name(), action, "");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> prepareCandidateResponseTransition(UUID applicationId, String responseType) {
        ApplicationEntity application = getApplication(applicationId);
        return prepareStatusTransition(application.getStatus().name(), "RESPOND_INVITATION", "");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> prepareCloseVacancyTransition(UUID vacancyId) {
        VacancyEntity vacancy = vacancyRepository.findById(vacancyId)
                .orElseThrow(() -> new CamundaFormValidationException("Vacancy not found: " + vacancyId));
        return prepareStatusTransition(vacancy.getStatus().name(), "CLOSE_VACANCY", VacancyStatus.CLOSED.name());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> prepareVacancyStatusTransition(UUID vacancyId, String requestedStatus) {
        VacancyEntity vacancy = vacancyRepository.findById(vacancyId)
                .orElseThrow(() -> new CamundaFormValidationException("Vacancy not found: " + vacancyId));
        return prepareStatusTransition(vacancy.getStatus().name(), "UPDATE_VACANCY_STATUS", requestedStatus);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateCreateVacancyForm(String starterUserId, UUID recruiterUserId, String title, String description,
                                                         Object requiredSkillsRaw, Object screeningThresholdRaw) {
        UserEntity recruiter = userResolver.resolveRecruiterForVacancyCommand(starterUserId, recruiterUserId);
        String normalizedTitle = formValueParser.normalizeRequiredText(title, "Vacancy title", 255);
        String normalizedDescription = formValueParser.normalizeOptionalText(description, "Vacancy description", 10_000);
        List<String> skills = formValueParser.parseRequiredSkills(requiredSkillsRaw);
        int threshold = formValueParser.requiredScreeningThreshold(screeningThresholdRaw);
        return Map.of(
                "formValidated", true,
                "formErrorMessage", "",
                "recruiterUserId", recruiter.getId(),
                "recruiterCamundaUserId", CamundaIdentitySyncService.camundaUserId(recruiter),
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
        UserEntity recruiter = userResolver.resolveRecruiterForVacancyCommand(starterUserId, recruiterUserId);
        String normalizedTitle = formValueParser.normalizeRequiredText(title, "Vacancy title", 255);
        String normalizedDescription = formValueParser.normalizeOptionalText(description, "Vacancy description", 10_000);
        List<String> skills = formValueParser.parseRequiredSkills(requiredSkillsRaw);
        int threshold = formValueParser.requiredScreeningThreshold(screeningThresholdRaw);

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

        return Map.ofEntries(
                Map.entry("vacancyCreated", true),
                Map.entry("vacancyId", vacancy.getId()),
                Map.entry("recruiterUserId", recruiter.getId()),
                Map.entry("recruiterCamundaUserId", CamundaIdentitySyncService.camundaUserId(recruiter)),
                Map.entry("title", vacancy.getTitle()),
                Map.entry("vacancyTitle", vacancy.getTitle()),
                Map.entry("description", vacancy.getDescription() == null ? "" : vacancy.getDescription()),
                Map.entry("requiredSkills", vacancy.getRequiredSkills()),
                Map.entry("screeningThreshold", vacancy.getScreeningThreshold()),
                Map.entry("status", vacancy.getStatus().name()),
                Map.entry("vacancyBusinessKey", businessKey)
        );
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateCloseVacancyForm(UUID vacancyId, String action, String reason) {
        formValidator.requiredChoice(action, "Vacancy action", Set.of("CLOSE"));
        formValidator.requiredText(reason, "Close reason", 5_000);
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
            var maybeInterview = interviewService.findActiveByApplicationId(application.getId());
            if (maybeInterview.isPresent()) {
                InterviewEntity interview = maybeInterview.get();
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
            clearInvitationState(application, reason);
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
    public Map<String, Object> validateVacancyStatusUpdate(UUID vacancyId, UUID recruiterUserId, String starterUserId, String requestedStatus) {
        VacancyEntity vacancy = vacancyRepository.findByIdForUpdate(vacancyId)
                .orElseThrow(() -> new CamundaFormValidationException("Vacancy not found: " + vacancyId));
        UserEntity recruiter = userResolver.resolveRecruiterForVacancyCommand(starterUserId, recruiterUserId);
        if (!userResolver.hasRole(recruiter, "RECRUITER")) {
            throw new CamundaFormValidationException("Only RECRUITER users can update vacancies");
        }
        if (!vacancy.getRecruiterUser().getId().equals(recruiter.getId())) {
            throw new CamundaFormValidationException("Vacancy does not belong to current recruiter");
        }
        formValueParser.parseVacancyStatus(requestedStatus);
        return Map.of(
                "formValidated", true,
                "formErrorMessage", "",
                "oldVacancyStatus", vacancy.getStatus().name(),
                "recruiterUserId", recruiter.getId(),
                "recruiterCamundaUserId", CamundaIdentitySyncService.camundaUserId(recruiter)
        );
    }

    @Transactional
    public Map<String, Object> applyVacancyStatusUpdate(UUID vacancyId, UUID recruiterUserId, String starterUserId, String requestedStatus) {
        validateVacancyStatusUpdate(vacancyId, recruiterUserId, starterUserId, requestedStatus);
        VacancyStatus newStatus = formValueParser.parseVacancyStatus(requestedStatus);
        VacancyEntity vacancy = vacancyRepository.findByIdForUpdate(vacancyId)
                .orElseThrow(() -> new IllegalArgumentException("Vacancy not found: " + vacancyId));
        VacancyStatus oldStatus = vacancy.getStatus();
        if (oldStatus != newStatus) {
            vacancy.setStatus(newStatus);
            UserEntity recruiter = userResolver.resolveRecruiterForVacancyCommand(starterUserId, recruiterUserId);
            vacancyHistoryService.record(vacancy, oldStatus, newStatus, recruiter);
        }
        return Map.of(
                "vacancyStatusUpdated", true,
                "vacancyId", vacancy.getId(),
                "oldVacancyStatus", oldStatus.name(),
                "status", vacancy.getStatus().name()
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

    private ApplicationEntity getApplication(UUID applicationId) {
        return applicationRepository.findDetailedById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Application not found: " + applicationId));
    }

    private void clearInvitationState(ApplicationEntity application, String reason) {
        application.setInvitationText(null);
        application.setInvitationSentAt(null);
        application.setInvitationExpiresAt(null);
        application.setResponseReceivedAt(null);
        application.setRecruiterComment(reason);
    }
}
