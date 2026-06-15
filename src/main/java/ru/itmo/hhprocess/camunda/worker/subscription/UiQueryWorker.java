package ru.itmo.hhprocess.camunda.worker.subscription;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.exception.CamundaFormValidationException;
import ru.itmo.hhprocess.utils.CamundaFormValidator;
import ru.itmo.hhprocess.camunda.CamundaIdentitySyncService;
import ru.itmo.hhprocess.config.CamundaProperties;
import ru.itmo.hhprocess.camunda.CamundaRestClient;
import ru.itmo.hhprocess.utils.CamundaTaskVariables;
import ru.itmo.hhprocess.dto.recruiter.WeekScheduleResponse;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.enums.VacancyStatus;
import ru.itmo.hhprocess.repository.ApplicationRepository;
import ru.itmo.hhprocess.repository.VacancyRepository;
import ru.itmo.hhprocess.service.InterviewService;
import ru.itmo.hhprocess.service.NotificationService;
import ru.itmo.hhprocess.service.ScheduleService;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "app.camunda.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
@CamundaWorkerSubscriptions.UiQuery
public class UiQueryWorker extends AbstractExternalTaskWorker {

    private static final int UI_PAYLOAD_MAX_LENGTH = 3500;

    private final VacancyRepository vacancyRepository;
    private final ApplicationRepository applicationRepository;
    private final InterviewService interviewService;
    private final ScheduleService scheduleService;
    private final NotificationService notificationService;
    private final CamundaRestClient camundaRestClient;
    private final CamundaProperties camundaProperties;
    private final CamundaIdentitySyncService camundaIdentitySyncService;
    private final ObjectMapper objectMapper;

    public UiQueryWorker(CamundaFormValidator formValidator,
                         VacancyRepository vacancyRepository,
                         ApplicationRepository applicationRepository,
                         InterviewService interviewService,
                         ScheduleService scheduleService,
                         NotificationService notificationService,
                         CamundaRestClient camundaRestClient,
                         CamundaProperties camundaProperties,
                         CamundaIdentitySyncService camundaIdentitySyncService,
                         ObjectMapper objectMapper) {
        super(formValidator);
        this.vacancyRepository = vacancyRepository;
        this.applicationRepository = applicationRepository;
        this.interviewService = interviewService;
        this.scheduleService = scheduleService;
        this.notificationService = notificationService;
        this.camundaRestClient = camundaRestClient;
        this.camundaProperties = camundaProperties;
        this.camundaIdentitySyncService = camundaIdentitySyncService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected Map<String, Object> handle(String activityId, CamundaTaskVariables variables) {
        return switch (activityId) {
            case "LoadCandidateVacancyList" -> loadCandidateVacancyList(variables.stringValue("starterUserId"));
            case "LoadCandidateApplicationList" -> loadCandidateApplicationList(variables.stringValue("starterUserId"));
            case "LoadCandidateApplicationView" -> loadCandidateApplicationView(variables.stringValue("starterUserId"), variables.stringValue("applicationIdText"));
            case "LoadRecruiterVacancyList" -> loadRecruiterVacancyList(variables.stringValue("starterUserId"));
            case "LoadRecruiterApplicationList" -> loadRecruiterApplicationList(variables.stringValue("starterUserId"));
            case "LoadRecruiterApplicationView" -> loadRecruiterApplicationView(variables.stringValue("starterUserId"), variables.stringValue("applicationIdText"));
            case "LoadRecruiterSchedule" -> loadRecruiterSchedule(variables.stringValue("starterUserId"), variables.readValue("weekOffset"));
            case "LoadNotificationList" -> loadNotificationList(variables.stringValue("starterUserId"));
            case "RunTimeoutReview" -> runTimeoutReview(variables.stringValue("starterUserId"));
            default -> Map.of("uiQueryIgnored", true, "activityId", activityId);
        };
    }

    private Map<String, Object> loadCandidateVacancyList(String starterUserId) {
        camundaIdentitySyncService.resolveUserFromCamundaStarter(starterUserId, "CANDIDATE");
        List<Map<String, Object>> vacancies = vacancyRepository.findAll().stream()
                .filter(vacancy -> vacancy.getStatus() == VacancyStatus.ACTIVE)
                .map(vacancy -> Map.<String, Object>of(
                        "id", vacancy.getId(),
                        "title", vacancy.getTitle(),
                        "description", vacancy.getDescription() == null ? "" : vacancy.getDescription(),
                        "requiredSkills", vacancy.getRequiredSkills(),
                        "screeningThreshold", vacancy.getScreeningThreshold(),
                        "status", vacancy.getStatus().name()
                ))
                .toList();
        return uiPayload("Активные вакансии", vacancies);
    }

    private Map<String, Object> loadCandidateApplicationList(String starterUserId) {
        UserEntity candidate = camundaIdentitySyncService.resolveUserFromCamundaStarter(starterUserId, "CANDIDATE");
        List<Map<String, Object>> applications = applicationRepository.findByCandidateUserId(candidate.getId()).stream()
                .map(application -> Map.<String, Object>of(
                        "applicationId", application.getId(),
                        "vacancyId", application.getVacancy().getId(),
                        "vacancyTitle", application.getVacancy().getTitle(),
                        "status", application.getStatus().toExternalStatus(),
                        "createdAt", application.getCreatedAt()
                ))
                .toList();
        return uiPayload("Мои отклики", applications);
    }

    private Map<String, Object> loadCandidateApplicationView(String starterUserId, String applicationIdText) {
        UserEntity candidate = camundaIdentitySyncService.resolveUserFromCamundaStarter(starterUserId, "CANDIDATE");
        ApplicationEntity application = getApplication(formValidator.requiredUuidText(applicationIdText, "applicationId"));
        if (!application.getCandidateUser().getId().equals(candidate.getId())) {
            throw new CamundaFormValidationException("Not your application");
        }
        return uiPayload("Отклик кандидата", applicationSummary(application));
    }

    private Map<String, Object> loadRecruiterVacancyList(String starterUserId) {
        UserEntity recruiter = camundaIdentitySyncService.resolveUserFromCamundaStarter(starterUserId, "RECRUITER");
        List<Map<String, Object>> vacancies = vacancyRepository.findByRecruiterUserId(recruiter.getId()).stream()
                .map(vacancy -> Map.<String, Object>of(
                        "id", vacancy.getId(),
                        "title", vacancy.getTitle(),
                        "status", vacancy.getStatus().name(),
                        "requiredSkills", vacancy.getRequiredSkills(),
                        "screeningThreshold", vacancy.getScreeningThreshold()
                ))
                .toList();
        return uiPayload("Вакансии рекрутера", vacancies);
    }

    private Map<String, Object> loadRecruiterApplicationList(String starterUserId) {
        UserEntity recruiter = camundaIdentitySyncService.resolveUserFromCamundaStarter(starterUserId, "RECRUITER");
        List<Map<String, Object>> applications = applicationRepository.findByRecruiterUserId(recruiter.getId()).stream()
                .map(this::applicationSummary)
                .toList();
        return uiPayload("Отклики по моим вакансиям", applications);
    }

    private Map<String, Object> loadRecruiterApplicationView(String starterUserId, String applicationIdText) {
        UserEntity recruiter = camundaIdentitySyncService.resolveUserFromCamundaStarter(starterUserId, "RECRUITER");
        ApplicationEntity application = getApplication(formValidator.requiredUuidText(applicationIdText, "applicationId"));
        if (!application.getVacancy().getRecruiterUser().getId().equals(recruiter.getId())) {
            throw new CamundaFormValidationException("Application does not belong to your vacancy");
        }
        return uiPayload("Отклик для рекрутера", applicationSummary(application));
    }

    private Map<String, Object> loadRecruiterSchedule(String starterUserId, Object weekOffsetRaw) {
        UserEntity recruiter = camundaIdentitySyncService.resolveUserFromCamundaStarter(starterUserId, "RECRUITER");
        int weekOffset = formValidator.optionalIntegerRange(weekOffsetRaw, "weekOffset", -52, 52, 0);
        return uiPayload("Расписание рекрутера", recruiterScheduleUiPayload(
                scheduleService.getRecruiterWeekSchedule(recruiter, weekOffset)));
    }

    private Map<String, Object> loadNotificationList(String starterUserId) {
        UserEntity user = camundaIdentitySyncService.resolveUserFromCamundaStarter(starterUserId, null);
        return uiPayload("Уведомления", notificationService.getNotificationsForUser(user.getId()));
    }

    private Map<String, Object> runTimeoutReview(String starterUserId) {
        camundaIdentitySyncService.resolveUserFromCamundaStarter(starterUserId, "ADMIN");
        String businessKey = "timeout-review:" + UUID.randomUUID();
        String processInstanceId = camundaRestClient.startProcessByKey(
                        camundaProperties.getTimeoutSchedulerProcessKey(),
                        businessKey,
                        Map.of(
                                "manualTimeoutReview", true,
                                "startedBy", starterUserId,
                                "startedAt", Instant.now()
                        ))
                .orElse("");
        return uiPayload("Ручная проверка таймаутов",
                Map.of(
                        "schedulerProcessStarted", !processInstanceId.isBlank(),
                        "processInstanceId", processInstanceId,
                        "businessKey", businessKey,
                        "owner", "Camunda BPMN loop hhTimeoutSchedulerProcess"
                ));
    }

    private ApplicationEntity getApplication(UUID applicationId) {
        return applicationRepository.findDetailedById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Application not found: " + applicationId));
    }

    private Map<String, Object> applicationSummary(ApplicationEntity application) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applicationId", application.getId());
        result.put("vacancyId", application.getVacancy().getId());
        result.put("vacancyTitle", application.getVacancy().getTitle());
        result.put("candidateId", application.getCandidateUser().getId());
        result.put("candidateEmail", application.getCandidateUser().getEmail());
        result.put("status", application.getStatus().name());
        result.put("resumeText", application.getResumeText());
        result.put("coverLetter", application.getCoverLetter() == null ? "" : application.getCoverLetter());
        result.put("invitationText", application.getInvitationText() == null ? "" : application.getInvitationText());
        result.put("invitationExpiresAt", application.getInvitationExpiresAt());
        result.put("createdAt", application.getCreatedAt());
        result.put("updatedAt", application.getUpdatedAt());
        interviewService.findActiveByApplicationId(application.getId()).ifPresent(interview -> {
            result.put("interviewId", interview.getId());
            result.put("interviewStatus", interview.getStatus().name());
            result.put("scheduledAt", interview.getScheduledAt());
            result.put("durationMinutes", interview.getDurationMinutes());
        });
        return result;
    }

    private Map<String, Object> recruiterScheduleUiPayload(WeekScheduleResponse schedule) {
        List<Map<String, Object>> items = schedule.getItems().stream()
                .map(item -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("start_at", item.getStartAt());
                    result.put("end_at", item.getEndAt());
                    result.put("application_id", item.getApplicationId());
                    result.put("candidate_email", item.getCandidateEmail());
                    result.put("interview_status", item.getInterviewStatus());
                    result.put("slot_status", item.getStatus());
                    return result;
                })
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("week_offset", schedule.getWeekOffset());
        result.put("week_start", schedule.getWeekStart());
        result.put("week_end", schedule.getWeekEnd());
        result.put("total_items", items.size());
        result.put("items", items);
        return result;
    }

    private Map<String, Object> uiPayload(String title, Object payload) {
        return Map.of(
                "uiTitle", title,
                "uiPayload", toCamundaUiJson(payload),
                "loadedAt", Instant.now().toString()
        );
    }

    private String toCamundaUiJson(Object payload) {
        try {
            return truncateUiPayload(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            return truncateUiPayload(String.valueOf(payload));
        }
    }

    private String truncateUiPayload(String value) {
        if (value == null || value.length() <= UI_PAYLOAD_MAX_LENGTH) {
            return value;
        }
        return value.substring(0, UI_PAYLOAD_MAX_LENGTH) + "\n... truncated for Camunda Tasklist form ...";
    }
}
