package ru.itmo.hhprocess.camunda;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.enums.VacancyStatus;
import ru.itmo.hhprocess.repository.ApplicationRepository;
import ru.itmo.hhprocess.repository.VacancyRepository;
import ru.itmo.hhprocess.service.NotificationService;
import ru.itmo.hhprocess.service.ScheduleService;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CamundaUiQueryAdapter {

    private final ApplicationRepository applicationRepository;
    private final VacancyRepository vacancyRepository;
    private final NotificationService notificationService;
    private final ScheduleService scheduleService;
    private final CamundaRestClient camundaRestClient;
    private final CamundaProperties camundaProperties;
    private final CamundaUserResolver userResolver;
    private final CamundaFormValueParser formValueParser;
    private final CamundaPayloadMapper payloadMapper;

    @Transactional(readOnly = true)
    public Map<String, Object> loadCandidateVacancyList(String starterUserId) {
        userResolver.resolveUserFromCamundaStarter(starterUserId, "CANDIDATE");
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
        return payloadMapper.uiPayload("Активные вакансии", vacancies);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> loadCandidateApplicationList(String starterUserId) {
        UserEntity candidate = userResolver.resolveUserFromCamundaStarter(starterUserId, "CANDIDATE");
        List<Map<String, Object>> applications = applicationRepository.findByCandidateUserId(candidate.getId()).stream()
                .map(application -> Map.<String, Object>of(
                        "applicationId", application.getId(),
                        "vacancyId", application.getVacancy().getId(),
                        "vacancyTitle", application.getVacancy().getTitle(),
                        "status", application.getStatus().toExternalStatus(),
                        "createdAt", application.getCreatedAt()
                ))
                .toList();
        return payloadMapper.uiPayload("Мои отклики", applications);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> loadCandidateApplicationView(String starterUserId, String applicationIdText) {
        UserEntity candidate = userResolver.resolveUserFromCamundaStarter(starterUserId, "CANDIDATE");
        ApplicationEntity application = getApplication(formValueParser.parseUuidText(applicationIdText, "applicationId"));
        if (!application.getCandidateUser().getId().equals(candidate.getId())) {
            throw new CamundaFormValidationException("Not your application");
        }
        return payloadMapper.uiPayload("Отклик кандидата", payloadMapper.applicationSummary(application));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> loadRecruiterVacancyList(String starterUserId) {
        UserEntity recruiter = userResolver.resolveUserFromCamundaStarter(starterUserId, "RECRUITER");
        List<Map<String, Object>> vacancies = vacancyRepository.findByRecruiterUserId(recruiter.getId()).stream()
                .map(vacancy -> Map.<String, Object>of(
                        "id", vacancy.getId(),
                        "title", vacancy.getTitle(),
                        "status", vacancy.getStatus().name(),
                        "requiredSkills", vacancy.getRequiredSkills(),
                        "screeningThreshold", vacancy.getScreeningThreshold()
                ))
                .toList();
        return payloadMapper.uiPayload("Вакансии рекрутера", vacancies);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> loadRecruiterApplicationList(String starterUserId) {
        UserEntity recruiter = userResolver.resolveUserFromCamundaStarter(starterUserId, "RECRUITER");
        List<Map<String, Object>> applications = applicationRepository.findByRecruiterUserId(recruiter.getId()).stream()
                .map(payloadMapper::applicationSummary)
                .toList();
        return payloadMapper.uiPayload("Отклики по моим вакансиям", applications);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> loadRecruiterApplicationView(String starterUserId, String applicationIdText) {
        UserEntity recruiter = userResolver.resolveUserFromCamundaStarter(starterUserId, "RECRUITER");
        ApplicationEntity application = getApplication(formValueParser.parseUuidText(applicationIdText, "applicationId"));
        if (!application.getVacancy().getRecruiterUser().getId().equals(recruiter.getId())) {
            throw new CamundaFormValidationException("Application does not belong to your vacancy");
        }
        return payloadMapper.uiPayload("Отклик для рекрутера", payloadMapper.applicationSummary(application));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> loadRecruiterSchedule(String starterUserId, Object weekOffsetRaw) {
        UserEntity recruiter = userResolver.resolveUserFromCamundaStarter(starterUserId, "RECRUITER");
        int weekOffset = formValueParser.parseWeekOffset(weekOffsetRaw);
        return payloadMapper.uiPayload("Расписание рекрутера", payloadMapper.recruiterScheduleUiPayload(
                scheduleService.getRecruiterWeekSchedule(recruiter, weekOffset)));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> loadNotificationList(String starterUserId) {
        UserEntity user = userResolver.resolveUserFromCamundaStarter(starterUserId, null);
        return payloadMapper.uiPayload("Уведомления", notificationService.getNotificationsForUser(user.getId()));
    }

    @Transactional
    public Map<String, Object> runTimeoutReview(String starterUserId) {
        userResolver.resolveUserFromCamundaStarter(starterUserId, "ADMIN");
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
        return payloadMapper.uiPayload("Ручная проверка таймаутов",
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
}
