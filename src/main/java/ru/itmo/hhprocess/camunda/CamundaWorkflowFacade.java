package ru.itmo.hhprocess.camunda;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.itmo.hhprocess.entity.ApplicationEntity;
import ru.itmo.hhprocess.entity.InterviewEntity;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.entity.VacancyEntity;
import ru.itmo.hhprocess.enums.ApplicationStatus;
import ru.itmo.hhprocess.enums.ErrorCode;
import ru.itmo.hhprocess.enums.ResponseType;
import ru.itmo.hhprocess.enums.VacancyStatus;
import ru.itmo.hhprocess.exception.ApiException;

import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;

@Slf4j
@Component
@RequiredArgsConstructor
public class CamundaWorkflowFacade {

    private static final String VACANCY_CREATED_RESULT_TASK = "VacancyCreatedResultTask";
    private static final String RECRUITER_DECISION_TASK = "RecruiterDecisionTask";
    private static final String WRITE_INVITATION_TASK = "WriteInvitationTask";
    private static final String CANDIDATE_RESPONSE_TASK = "CandidateInvitationResponseTask";
    private static final String MANAGE_VACANCY_TASK = "ManageVacancyTask";
    private static final String ADMIN_RESET_APPROVAL_TASK = "AdminResetApprovalTask";
    private static final String UPDATE_VACANCY_STATUS_TASK = "UpdateVacancyStatusTask";
    private static final String CANCEL_INTERVIEW_TASK = "CancelInterviewTask";
    private static final String RECRUITER_GROUP = "RECRUITER";
    private static final String CANDIDATE_GROUP = "CANDIDATE";
    private static final String ADMIN_GROUP = "ADMIN";

    private final CamundaRestClient camundaRestClient;
    private final CamundaProperties properties;

    public Optional<String> startVacancyCreateFromRequest(UserEntity recruiterUser, String title, String description,
                                                          List<String> requiredSkills, int screeningThreshold) {
        String requestBusinessKey = "vacancy-request:" + UUID.randomUUID();
        Optional<String> processInstanceId = camundaRestClient.startProcessByKey(
                properties.getVacancyProcessKey(),
                requestBusinessKey,
                Map.of(
                        "recruiterUserId", recruiterUser.getId(),
                        "title", safe(title),
                        "description", safe(description),
                        "requiredSkills", requiredSkills == null ? "" : String.join(", ", requiredSkills),
                        "screeningThreshold", screeningThreshold,
                        "restAutoSubmit", true,
                        "startedAt", Instant.now()
                )
        );
        return processInstanceId;
    }

    public Optional<String> startVacancyProcess(VacancyEntity vacancy) {
        Optional<String> processInstanceId = camundaRestClient.startProcessByKey(
                properties.getVacancyProcessKey(),
                vacancyBusinessKey(vacancy.getId()),
                Map.of(
                        "vacancyId", vacancy.getId(),
                        "recruiterUserId", vacancy.getRecruiterUser().getId(),
                        "title", vacancy.getTitle(),
                        "restAutoSubmit", true,
                        "status", vacancy.getStatus().name()
                )
        );
        return processInstanceId;
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
        variables.put("restAutoSubmit", true);
        Optional<String> processInstanceId = camundaRestClient.startProcessByKey(
                properties.getApplicationProcessKey(),
                applicationBusinessKey(application.getId()),
                variables
        );
        return processInstanceId;
    }

    public Optional<String> startApplicationCreateFromRequest(VacancyEntity vacancy, UserEntity candidateUser,
                                                             String resumeText, String coverLetter) {
        String requestBusinessKey = "application-request:" + UUID.randomUUID();
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("vacancyId", vacancy.getId());
        variables.put("candidateUserId", candidateUser.getId());
        variables.put("recruiterUserId", vacancy.getRecruiterUser().getId());
        variables.put("vacancyTitle", vacancy.getTitle());
        variables.put("resumeText", safe(resumeText));
        variables.put("coverLetter", safe(coverLetter));
        variables.put("status", ApplicationStatus.SCREENING_IN_PROGRESS.name());
        variables.put("restAutoSubmit", true);
        Optional<String> processInstanceId = camundaRestClient.startProcessByKey(
                properties.getApplicationProcessKey(),
                requestBusinessKey,
                variables
        );
        return processInstanceId;
    }

    public void ensureApplicationProcessActive(ApplicationEntity application) {
        if (camundaRestClient.hasActiveProcessInstance(properties.getApplicationProcessKey(), applicationBusinessKey(application.getId()))) {
            return;
        }
        startApplicationProcess(application);
    }

    public boolean recruiterRejected(ApplicationEntity application, UserEntity recruiterUser, String comment) {
        assertRecruiterCanComplete(application, recruiterUser);
        Map<String, Object> variables = Map.of(
                "decision", "REJECT",
                "recruiterComment", safe(comment),
                "decidedAt", Instant.now()
        );
        if (completeApplicationTask(application.getId(), RECRUITER_DECISION_TASK, RECRUITER_GROUP, recruiterUser.getId(), variables)) {
            return true;
        }
        return completeApplicationTask(application.getId(), CANDIDATE_RESPONSE_TASK, Map.of(
                "responseType", "RECRUITER_REJECT",
                "decision", "REJECT",
                "recruiterComment", safe(comment),
                "decidedAt", Instant.now(),
                "completedByUserId", recruiterUser.getId(),
                "completedByGroup", RECRUITER_GROUP
        ));
    }

    public boolean recruiterInvited(ApplicationEntity application, UserEntity recruiterUser, String message, Instant scheduledAt, Integer durationMinutes, Instant expiresAt) {
        assertRecruiterCanComplete(application, recruiterUser);
        UUID applicationId = application.getId();
        boolean decisionCompleted = completeApplicationTask(applicationId, RECRUITER_DECISION_TASK, RECRUITER_GROUP, recruiterUser.getId(), Map.of(
                "decision", "INVITE",
                "decidedAt", Instant.now()
        ));
        boolean invitationCompleted = completeApplicationTask(applicationId, WRITE_INVITATION_TASK, RECRUITER_GROUP, recruiterUser.getId(), Map.of(
                "invitationMessage", safe(message),
                "scheduledAt", scheduledAt,
                "durationMinutes", durationMinutes,
                "invitationExpiresAt", expiresAt,
                "invitationTimeoutDuration", "PT48H"
        ));
        return decisionCompleted && invitationCompleted;
    }

    public boolean invitationResponded(ApplicationEntity application, UserEntity candidateUser, ResponseType responseType, String message) {
        assertCandidateCanComplete(application, candidateUser);
        return completeApplicationTask(application.getId(), CANDIDATE_RESPONSE_TASK, CANDIDATE_GROUP, candidateUser.getId(), Map.of(
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

    public boolean returnInvitationToRecruiterReview(ApplicationEntity application, String reason, String responseType) {
        Map<String, Object> variables = Map.of(
                "responseType", responseType,
                "cancelReason", safe(reason),
                "returnedToRecruiterReviewAt", Instant.now()
        );
        if (completeApplicationTask(application.getId(), CANDIDATE_RESPONSE_TASK, variables)) {
            return true;
        }
        return !camundaRestClient.findActiveTasks(applicationBusinessKey(application.getId()), RECRUITER_DECISION_TASK).isEmpty();
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

    public boolean closeVacancy(VacancyEntity vacancy, UserEntity recruiterUser, String reason) {
        assertVacancyRecruiterCanComplete(vacancy, recruiterUser);
        completeTaskIfActive(vacancyBusinessKey(vacancy.getId()), VACANCY_CREATED_RESULT_TASK, RECRUITER_GROUP,
                recruiterUser.getId(), Map.of(
                        "resultAcknowledged", true,
                        "acknowledgedAt", Instant.now()
                ));
        return completeVacancyTask(vacancy.getId(), MANAGE_VACANCY_TASK, RECRUITER_GROUP, recruiterUser.getId(), Map.of(
                "action", "CLOSE",
                "closeReason", safe(reason),
                "closedAt", Instant.now()
        ));
    }

    public Optional<String> updateVacancyStatus(VacancyEntity vacancy, UserEntity recruiterUser, VacancyStatus status) {
        assertVacancyRecruiterCanComplete(vacancy, recruiterUser);
        String businessKey = "vacancy-status:" + vacancy.getId() + ":" + UUID.randomUUID();
        Optional<String> processInstanceId = camundaRestClient.startProcessByKey(
                properties.getVacancyStatusUpdateProcessKey(),
                businessKey,
                Map.of(
                        "vacancyId", vacancy.getId(),
                        "recruiterUserId", recruiterUser.getId(),
                        "requestedStatus", status.name(),
                        "requestedAt", Instant.now()
                )
        );
        processInstanceId.ifPresent(id -> completeTaskInProcessInstance(
                id,
                UPDATE_VACANCY_STATUS_TASK,
                RECRUITER_GROUP,
                recruiterUser.getId(),
                Map.of(
                        "requestedStatus", status.name(),
                        "updatedAt", Instant.now()
                )));
        return processInstanceId;
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

    public boolean adminResetInterview(InterviewEntity interview, UserEntity adminUser, String reason) {
        ApplicationEntity application = interview.getApplication();
        String businessKey = "admin-reset:" + interview.getId() + ":" + UUID.randomUUID();
        Optional<String> processInstanceId = camundaRestClient.startProcessByKey(
                properties.getAdminInterviewResetProcessKey(),
                businessKey,
                Map.of(
                        "interviewId", interview.getId(),
                        "applicationId", application.getId(),
                        "vacancyId", application.getVacancy().getId(),
                        "candidateUserId", application.getCandidateUser().getId(),
                        "recruiterUserId", application.getVacancy().getRecruiterUser().getId(),
                        "adminUserId", adminUser.getId(),
                        "resetReason", safe(reason),
                        "requestedAt", Instant.now()
                )
        );
        return processInstanceId.isPresent()
                && completeTaskInProcessInstance(processInstanceId.get(), ADMIN_RESET_APPROVAL_TASK, ADMIN_GROUP, adminUser.getId(), Map.of(
                "approvedByAdminUserId", adminUser.getId(),
                "resetReason", safe(reason),
                "approvedAt", Instant.now()
        ));
    }

    public Optional<String> recruiterCancelInterview(InterviewEntity interview, UserEntity recruiterUser, String reason) {
        ApplicationEntity application = interview.getApplication();
        assertRecruiterCanComplete(application, recruiterUser);
        String businessKey = "interview-cancel:" + interview.getId() + ":" + UUID.randomUUID();
        Optional<String> processInstanceId = camundaRestClient.startProcessByKey(
                properties.getRecruiterInterviewCancelProcessKey(),
                businessKey,
                Map.of(
                        "interviewId", interview.getId(),
                        "applicationId", application.getId(),
                        "vacancyId", application.getVacancy().getId(),
                        "candidateUserId", application.getCandidateUser().getId(),
                        "recruiterUserId", recruiterUser.getId(),
                        "cancelReason", safe(reason),
                        "requestedAt", Instant.now()
                )
        );
        processInstanceId.ifPresent(id -> completeTaskInProcessInstance(
                id,
                CANCEL_INTERVIEW_TASK,
                RECRUITER_GROUP,
                recruiterUser.getId(),
                Map.of(
                        "cancelReason", safe(reason),
                        "cancelledByRecruiterUserId", recruiterUser.getId(),
                        "cancelledAt", Instant.now()
                )));
        return processInstanceId;
    }

    private boolean completeApplicationTask(UUID applicationId, String taskDefinitionKey, Map<String, ?> variables) {
        return completeApplicationTask(applicationId, taskDefinitionKey, null, null, variables);
    }

    private boolean completeApplicationTask(UUID applicationId, String taskDefinitionKey, String expectedGroup,
                                            UUID actorUserId, Map<String, ?> variables) {
        return completeTask(applicationBusinessKey(applicationId), taskDefinitionKey, expectedGroup, actorUserId, variables);
    }

    private boolean completeVacancyTask(UUID vacancyId, String taskDefinitionKey, String expectedGroup,
                                        UUID actorUserId, Map<String, ?> variables) {
        return completeTask(vacancyBusinessKey(vacancyId), taskDefinitionKey, expectedGroup, actorUserId, variables);
    }

    private boolean completeTaskIfActive(String businessKey, String taskDefinitionKey, String expectedGroup,
                                         UUID actorUserId, Map<String, ?> variables) {
        var tasks = camundaRestClient.findActiveTasks(businessKey, taskDefinitionKey);
        if (tasks.isEmpty()) {
            return false;
        }
        Object id = tasks.get(0).get("id");
        if (id == null) {
            return false;
        }
        String taskId = String.valueOf(id);
        if (expectedGroup != null && !camundaRestClient.taskHasCandidateGroup(taskId, expectedGroup)) {
            log.warn("Camunda task {} for businessKey={} does not expose candidate group {}", taskDefinitionKey, businessKey, expectedGroup);
            return false;
        }
        Map<String, Object> completedVariables = new LinkedHashMap<>(variables);
        if (actorUserId != null) {
            completedVariables.put("completedByUserId", actorUserId);
        }
        if (expectedGroup != null) {
            completedVariables.put("completedByGroup", expectedGroup);
        }
        boolean completed = camundaRestClient.completeTask(taskId, completedVariables);
        if (completed) {
            log.info("Completed Camunda task {}; businessKey={}; taskId={}", taskDefinitionKey, businessKey, taskId);
        }
        return completed;
    }

    private boolean completeTask(String businessKey, String taskDefinitionKey, String expectedGroup,
                                 UUID actorUserId, Map<String, ?> variables) {
        for (int attempt = 1; attempt <= 20; attempt++) {
            var tasks = camundaRestClient.findActiveTasks(businessKey, taskDefinitionKey);
            if (!tasks.isEmpty()) {
                Object id = tasks.get(0).get("id");
                if (id != null) {
                    String taskId = String.valueOf(id);
                    if (expectedGroup != null && !camundaRestClient.taskHasCandidateGroup(taskId, expectedGroup)) {
                        log.warn("Camunda task {} for businessKey={} does not expose candidate group {}", taskDefinitionKey, businessKey, expectedGroup);
                        return false;
                    }
                    Map<String, Object> completedVariables = new LinkedHashMap<>(variables);
                    if (actorUserId != null) {
                        completedVariables.put("completedByUserId", actorUserId);
                    }
                    if (expectedGroup != null) {
                        completedVariables.put("completedByGroup", expectedGroup);
                    }
                    boolean completed = camundaRestClient.completeTask(taskId, completedVariables);
                    if (completed) {
                        log.info("Completed Camunda task {}; businessKey={}; taskId={}", taskDefinitionKey, businessKey, taskId);
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

    private boolean completeTaskInProcessInstance(String processInstanceId, String taskDefinitionKey, String expectedGroup,
                                                  UUID actorUserId, Map<String, ?> variables) {
        for (int attempt = 1; attempt <= 20; attempt++) {
            var tasks = camundaRestClient.findActiveTasksByProcessInstanceId(processInstanceId, taskDefinitionKey);
            if (!tasks.isEmpty()) {
                Object id = tasks.get(0).get("id");
                if (id != null) {
                    String taskId = String.valueOf(id);
                    if (expectedGroup != null && !camundaRestClient.taskHasCandidateGroup(taskId, expectedGroup)) {
                        log.warn("Camunda task {} for processInstanceId={} does not expose candidate group {}",
                                taskDefinitionKey, processInstanceId, expectedGroup);
                        return false;
                    }
                    Map<String, Object> completedVariables = new LinkedHashMap<>(variables);
                    if (actorUserId != null) {
                        completedVariables.put("completedByUserId", actorUserId);
                    }
                    if (expectedGroup != null) {
                        completedVariables.put("completedByGroup", expectedGroup);
                    }
                    boolean completed = camundaRestClient.completeTask(taskId, completedVariables);
                    if (completed) {
                        log.info("Completed Camunda task {}; processInstanceId={}; taskId={}",
                                taskDefinitionKey, processInstanceId, taskId);
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
        log.warn("Camunda task {} for processInstanceId={} was not found", taskDefinitionKey, processInstanceId);
        return false;
    }

    private static void assertRecruiterCanComplete(ApplicationEntity application, UserEntity recruiterUser) {
        if (!application.getVacancy().getRecruiterUser().getId().equals(recruiterUser.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.AUTH_ACCESS_DENIED,
                    "Application does not belong to your vacancy");
        }
    }

    private static void assertCandidateCanComplete(ApplicationEntity application, UserEntity candidateUser) {
        if (!application.getCandidateUser().getId().equals(candidateUser.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.AUTH_ACCESS_DENIED,
                    "Not your application");
        }
    }

    private static void assertVacancyRecruiterCanComplete(VacancyEntity vacancy, UserEntity recruiterUser) {
        if (!vacancy.getRecruiterUser().getId().equals(recruiterUser.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.AUTH_ACCESS_DENIED,
                    "Vacancy does not belong to current recruiter");
        }
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
