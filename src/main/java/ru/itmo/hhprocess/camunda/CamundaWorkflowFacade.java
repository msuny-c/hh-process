package ru.itmo.hhprocess.camunda;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.VacancyEntity;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.enums.ResponseType;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class CamundaWorkflowFacade {

    private static final String RECRUITER_DECISION_TASK = "RecruiterDecisionTask";
    private static final String WRITE_INVITATION_TASK = "WriteInvitationTask";
    private static final String CANDIDATE_RESPONSE_TASK = "CandidateInvitationResponseTask";
    private static final String MANAGE_VACANCY_TASK = "ManageVacancyTask";

    private final CamundaRestClient camundaRestClient;
    private final CamundaProperties properties;

    public Optional<String> startVacancyProcess(VacancyEntity vacancy) {
        return camundaRestClient.startProcessByKey(
                properties.getVacancyProcessKey(),
                vacancyBusinessKey(vacancy.getId()),
                Map.of(
                        "vacancyId", vacancy.getId(),
                        "recruiterUserId", vacancy.getRecruiterUser().getId(),
                        "title", vacancy.getTitle(),
                        "status", vacancy.getStatus().name()
                )
        );
    }

    public Optional<String> startApplicationProcess(ApplicationEntity application) {
        boolean screeningPassed = application.getStatus() != ApplicationStatus.SCREENING_FAILED;
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("applicationId", application.getId());
        variables.put("vacancyId", application.getVacancy().getId());
        variables.put("candidateUserId", application.getCandidateUser().getId());
        variables.put("recruiterUserId", application.getVacancy().getRecruiterUser().getId());
        variables.put("vacancyTitle", application.getVacancy().getTitle());
        variables.put("screeningPassed", screeningPassed);
        variables.put("status", application.getStatus().name());
        return camundaRestClient.startProcessByKey(
                properties.getApplicationProcessKey(),
                applicationBusinessKey(application.getId()),
                variables
        );
    }

    public boolean recruiterRejected(ApplicationEntity application, String comment) {
        return completeApplicationTask(application.getId(), RECRUITER_DECISION_TASK, Map.of(
                "decision", "REJECT",
                "recruiterComment", safe(comment),
                "decidedAt", Instant.now()
        ));
    }

    public boolean recruiterInvited(ApplicationEntity application, String message, Instant scheduledAt, Integer durationMinutes, Instant expiresAt) {
        UUID applicationId = application.getId();
        boolean decisionCompleted = completeApplicationTask(applicationId, RECRUITER_DECISION_TASK, Map.of(
                "decision", "INVITE",
                "decidedAt", Instant.now()
        ));
        boolean invitationCompleted = completeApplicationTask(applicationId, WRITE_INVITATION_TASK, Map.of(
                "invitationMessage", safe(message),
                "scheduledAt", scheduledAt,
                "durationMinutes", durationMinutes,
                "invitationExpiresAt", expiresAt,
                "invitationTimeoutDuration", "PT48H"
        ));
        return decisionCompleted && invitationCompleted;
    }

    public boolean invitationResponded(ApplicationEntity application, ResponseType responseType, String message) {
        return completeApplicationTask(application.getId(), CANDIDATE_RESPONSE_TASK, Map.of(
                "responseType", responseType.name(),
                "responseMessage", safe(message),
                "responseReceivedAt", Instant.now()
        ));
    }

    public boolean invitationTimedOut(ApplicationEntity application) {
        return completeApplicationTask(application.getId(), CANDIDATE_RESPONSE_TASK, Map.of(
                "responseType", "TIMEOUT",
                "timeoutAt", Instant.now()
        ));
    }

    public boolean applicationClosedByVacancy(ApplicationEntity application, String reason) {
        Map<String, Object> variables = Map.of(
                "decision", "VACANCY_CLOSED",
                "responseType", "VACANCY_CLOSED",
                "closeReason", safe(reason),
                "closedAt", Instant.now()
        );
        if (completeApplicationTask(application.getId(), RECRUITER_DECISION_TASK, variables)) {
            return true;
        }
        if (completeApplicationTask(application.getId(), WRITE_INVITATION_TASK, variables)) {
            return true;
        }
        return completeApplicationTask(application.getId(), CANDIDATE_RESPONSE_TASK, variables);
    }

    public boolean closeVacancy(VacancyEntity vacancy, String reason) {
        return completeVacancyTask(vacancy.getId(), MANAGE_VACANCY_TASK, Map.of(
                "action", "CLOSE",
                "closeReason", safe(reason),
                "closedAt", Instant.now()
        ));
    }

    public void startTimeoutSchedulerIfNeeded() {
        if (camundaRestClient.hasActiveProcessInstance(properties.getTimeoutSchedulerProcessKey(), "timeout-scheduler")) {
            log.info("Camunda timeout scheduler process is already active");
            return;
        }
        camundaRestClient.startProcessByKey(
                properties.getTimeoutSchedulerProcessKey(),
                "timeout-scheduler",
                Map.of("startedAt", Instant.now())
        );
    }

    private boolean completeApplicationTask(UUID applicationId, String taskDefinitionKey, Map<String, ?> variables) {
        return completeTask(applicationBusinessKey(applicationId), taskDefinitionKey, variables);
    }

    private boolean completeVacancyTask(UUID vacancyId, String taskDefinitionKey, Map<String, ?> variables) {
        return completeTask(vacancyBusinessKey(vacancyId), taskDefinitionKey, variables);
    }

    private boolean completeTask(String businessKey, String taskDefinitionKey, Map<String, ?> variables) {
        for (int attempt = 1; attempt <= 20; attempt++) {
            var tasks = camundaRestClient.findActiveTasks(businessKey, taskDefinitionKey);
            if (!tasks.isEmpty()) {
                Object id = tasks.get(0).get("id");
                if (id != null) {
                    boolean completed = camundaRestClient.completeTask(String.valueOf(id), variables);
                    if (completed) {
                        log.info("Completed Camunda task {}; businessKey={}; taskId={}", taskDefinitionKey, businessKey, id);
                    }
                    return completed;
                }
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        log.warn("Camunda task {} for businessKey={} was not found", taskDefinitionKey, businessKey);
        return false;
    }

    private static String applicationBusinessKey(UUID applicationId) {
        return "application:" + applicationId;
    }

    private static String vacancyBusinessKey(UUID vacancyId) {
        return "vacancy:" + vacancyId;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
