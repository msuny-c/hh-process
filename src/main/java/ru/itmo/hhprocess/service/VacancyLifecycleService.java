package ru.itmo.hhprocess.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.utils.CamundaFormValidator;
import ru.itmo.hhprocess.camunda.CamundaRestClient;
import ru.itmo.hhprocess.dto.recruiter.CloseVacancyRequest;
import ru.itmo.hhprocess.dto.recruiter.VacancyResponse;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.InterviewEntity;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.entity.VacancyEntity;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.enums.ErrorCode;
import ru.itmo.hhprocess.enums.InterviewStatus;
import ru.itmo.hhprocess.enums.VacancyStatus;
import ru.itmo.hhprocess.exception.ApiException;
import ru.itmo.hhprocess.mapper.VacancyMapper;
import ru.itmo.hhprocess.repository.ApplicationRepository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VacancyLifecycleService {

    private static final List<ApplicationStatus> ACTIVE_APPLICATION_STATUSES = List.of(
            ApplicationStatus.SCREENING_IN_PROGRESS,
            ApplicationStatus.ON_RECRUITER_REVIEW,
            ApplicationStatus.INVITED,
            ApplicationStatus.INVITATION_RESPONDED
    );

    private final VacancyService vacancyService;
    private final ApplicationRepository applicationRepository;
    private final InterviewService interviewService;
    private final ScheduleService scheduleService;
    private final HistoryService historyService;
    private final VacancyHistoryService vacancyHistoryService;
    private final NotificationService notificationService;
    private final VacancyMapper vacancyMapper;
    private final CamundaFormValidator formValidator;
    private final CamundaRestClient camundaRestClient;

    public VacancyResponse closeVacancy(UUID vacancyId, CloseVacancyRequest request) {
        UserEntity recruiterUser = vacancyService.getRecruiterUserForCurrentUser();
        VacancyEntity vacancy = vacancyService.findByIdForUpdate(vacancyId);
        vacancyService.ensureOwnership(vacancy, recruiterUser);
        if (vacancy.getStatus() == VacancyStatus.CLOSED) {
            return vacancyMapper.toResponse(vacancy);
        }

        if (!camundaRestClient.completeFirstTask("vacancy:" + vacancyId, "ManageVacancyTask",
                Map.of("action", "CLOSE", "closeReason", request.getReason() == null ? "" : request.getReason(), "closedAt", Instant.now()))) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_VACANCY_STATE,
                    "Camunda vacancy management task is not active");
        }

        vacancy = waitForVacancyClosed(vacancyId);
        waitForApplicationsClosed(vacancyId);
        return vacancyMapper.toResponse(vacancy);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validateCloseVacancyForm(UUID vacancyId, String action, String reason) {
        formValidator.requiredChoice(action, "Vacancy action", Set.of("CLOSE"));
        formValidator.requiredText(reason, "Close reason", 5_000);
        vacancyService.findById(vacancyId);
        return Map.of("formValidated", true, "formErrorMessage", "");
    }

    @Transactional
    public Map<String, Object> markVacancyClosed(UUID vacancyId) {
        VacancyEntity vacancy = vacancyService.findByIdForUpdate(vacancyId);
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
            if (interviewService.findActiveByApplicationId(application.getId()).isPresent()) {
                InterviewEntity interview = interviewService.findActiveByApplicationId(application.getId()).get();
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
        VacancyEntity vacancy = vacancyService.findByIdForUpdate(vacancyId);
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
            application.setInvitationText(null);
            application.setInvitationSentAt(null);
            application.setInvitationExpiresAt(null);
            application.setResponseReceivedAt(null);
            application.setRecruiterComment(reason);
            historyService.record(application, oldStatus, ApplicationStatus.CLOSED_BY_VACANCY, vacancy.getRecruiterUser());
            closedCount++;
        }
        return Map.of("closedApplicationsCount", closedCount, "status", vacancy.getStatus().name());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> recordVacancyClosedHistory(UUID vacancyId) {
        VacancyEntity vacancy = vacancyService.findByIdForUpdate(vacancyId);
        return Map.of("vacancyHistoryRecorded", true, "status", vacancy.getStatus().name());
    }

    @Transactional
    public Map<String, Object> closeVacancyApplications(UUID vacancyId, String reason) {
        Map<String, Object> dbResult = closeVacancyApplicationsInDb(vacancyId, reason);
        notificationService.notifyVacancyClosedCandidates(vacancyId);
        correlateVacancyClosedApplications(vacancyId, reason);
        return dbResult;
    }

    @Transactional
    public Map<String, Object> closeVacancyApplicationsInDb(UUID vacancyId, String reason) {
        VacancyEntity vacancy = vacancyService.findByIdForUpdate(vacancyId);
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
            application.setInvitationText(null);
            application.setInvitationSentAt(null);
            application.setInvitationExpiresAt(null);
            application.setResponseReceivedAt(null);
            application.setRecruiterComment(reason);
            historyService.record(application, oldStatus, ApplicationStatus.CLOSED_BY_VACANCY, vacancy.getRecruiterUser());
            closedCount++;
        }
        return Map.of("vacancyClosed", true, "closedApplicationsCount", closedCount, "status", vacancy.getStatus().name());
    }

    @Transactional
    public Map<String, Object> correlateVacancyClosedApplications(UUID vacancyId, String reason) {
        VacancyEntity vacancy = vacancyService.findByIdForUpdate(vacancyId);
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

    @Transactional
    public Map<String, Object> rollbackVacancyTransaction(UUID vacancyId, String reason) {
        VacancyEntity vacancy = vacancyService.findByIdForUpdate(vacancyId);
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

    private VacancyEntity waitForVacancyClosed(UUID vacancyId) {
        for (int attempt = 0; attempt < 24; attempt++) {
            VacancyEntity vacancy = vacancyService.findById(vacancyId);
            if (vacancy.getStatus() == VacancyStatus.CLOSED) {
                return vacancy;
            }
            sleep();
        }
        throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_VACANCY_STATE,
                "Camunda process did not close vacancy in time");
    }

    private void waitForApplicationsClosed(UUID vacancyId) {
        for (int attempt = 0; attempt < 24; attempt++) {
            if (applicationRepository.findByVacancyIdAndStatusIn(vacancyId, ACTIVE_APPLICATION_STATUSES).isEmpty()) {
                return;
            }
            sleep();
        }
        throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_VACANCY_STATE,
                "Camunda process did not close active applications in time");
    }

    private static void sleep() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_VACANCY_STATE,
                    "Interrupted while waiting for Camunda process");
        }
    }
}
