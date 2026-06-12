package ru.itmo.hhprocess.camunda;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.repository.UserRepository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.camunda.task-listener", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CamundaTaskListenerAdapter {

    private static final int MAX_TASKS_PER_PASS = 500;

    private final CamundaRestClient camundaRestClient;
    private final UserRepository userRepository;

    @Scheduled(fixedDelayString = "${app.camunda.task-listener.poll-interval-ms:5000}",
            initialDelayString = "${app.camunda.task-listener.initial-delay-ms:12000}")
    @Transactional(readOnly = true)
    public void reconcileActiveUserTasks() {
        if (!camundaRestClient.isEnabled()) {
            return;
        }
        for (Map<String, Object> task : camundaRestClient.findActiveUserTasks(MAX_TASKS_PER_PASS)) {
            reconcileTask(task);
        }
    }

    void reconcileTask(Map<String, Object> task) {
        String taskId = stringValue(task.get("id"));
        if (taskId.isBlank()) {
            return;
        }
        String taskDefinitionKey = stringValue(task.get("taskDefinitionKey"));
        String existingAssignee = stringValue(task.get("assignee"));

        Map<String, Object> variables = camundaRestClient.getTaskVariables(taskId);
        TaskOwner owner = resolveOwner(taskDefinitionKey, variables).orElse(null);
        if (owner == null) {
            return;
        }
        if (!camundaRestClient.taskHasCandidateGroup(taskId, owner.expectedGroup())) {
            log.debug("Skip Camunda task listener assignment: taskId={}, taskDefinitionKey={}, expectedGroup={} is absent",
                    taskId, taskDefinitionKey, owner.expectedGroup());
            return;
        }

        String camundaUserId = CamundaIdentitySyncService.camundaUserId(owner.user());
        if (existingAssignee.isBlank() || !existingAssignee.equals(camundaUserId)) {
            camundaRestClient.setTaskAssignee(taskId, camundaUserId);
        }
        camundaRestClient.ensureTaskUserAuthorization(camundaUserId, taskId);
    }

    private Optional<TaskOwner> resolveOwner(String taskDefinitionKey, Map<String, Object> variables) {
        return switch (taskDefinitionKey) {
            case "ApplyToVacancyTask", "CandidateInvitationResponseTask", "ScreeningResultTask",
                 "RejectionResultTask", "TimeoutResultTask", "CandidateResponseResultTask",
                 "VacancyClosedApplicationResultTask", "EnterCandidateApplicationId",
                 "DisplayCandidateApplicationView", "DisplayCandidateApplicationList",
                 "DisplayCandidateVacancyList" -> ownerFromVariable(variables, "candidateUserId", "CANDIDATE")
                    .or(() -> ownerFromStarter(variables, "CANDIDATE"));
            case "RecruiterDecisionTask", "WriteInvitationTask", "CreateVacancyTask", "ManageVacancyTask",
                 "VacancyCreatedResultTask", "VacancyClosedResultTask", "UpdateVacancyStatusTask",
                 "VacancyStatusResultTask", "CancelInterviewTask", "RecruiterCancelResultTask",
                 "EnterRecruiterApplicationId", "EnterScheduleWeek", "DisplayRecruiterApplicationView",
                 "DisplayRecruiterApplicationList", "DisplayRecruiterVacancyList", "DisplayRecruiterSchedule" ->
                    ownerFromVariable(variables, "recruiterUserId", "RECRUITER")
                            .or(() -> ownerFromStarter(variables, "RECRUITER"));
            case "AdminResetApprovalTask", "AdminResetResultTask", "ConfirmTimeoutReview", "DisplayTimeoutReview" ->
                    ownerFromVariable(variables, "adminUserId", "ADMIN")
                            .or(() -> ownerFromStarter(variables, "ADMIN"));
            case "DisplayNotificationList" -> ownerFromStarter(variables, null);
            default -> Optional.empty();
        };
    }

    private Optional<TaskOwner> ownerFromVariable(Map<String, Object> variables, String variableName, String expectedGroup) {
        Object raw = CamundaVariable.readValue(variables, variableName);
        if (raw == null || String.valueOf(raw).isBlank()) {
            return Optional.empty();
        }
        try {
            UUID userId = UUID.fromString(String.valueOf(raw));
            return userRepository.findById(userId)
                    .filter(UserEntity::isEnabled)
                    .filter(user -> expectedGroup == null || hasRole(user, expectedGroup))
                    .map(user -> new TaskOwner(user, expectedGroup));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private Optional<TaskOwner> ownerFromStarter(Map<String, Object> variables, String expectedGroup) {
        Object raw = CamundaVariable.readValue(variables, "starterUserId");
        if (raw == null || String.valueOf(raw).isBlank()) {
            return Optional.empty();
        }
        String starter = String.valueOf(raw).trim().toLowerCase(java.util.Locale.ROOT);
        return userRepository.findWithRolesByEmail(starter)
                .or(() -> userRepository.findAll().stream()
                        .filter(user -> starter.equals(CamundaIdentitySyncService.camundaUserId(user)))
                        .findFirst())
                .filter(UserEntity::isEnabled)
                .filter(user -> expectedGroup == null || hasRole(user, expectedGroup))
                .map(user -> new TaskOwner(user, expectedGroup == null ? primaryGroup(user) : expectedGroup));
    }

    private String primaryGroup(UserEntity user) {
        if (hasRole(user, "ADMIN")) {
            return "ADMIN";
        }
        if (hasRole(user, "RECRUITER")) {
            return "RECRUITER";
        }
        return "CANDIDATE";
    }

    private boolean hasRole(UserEntity user, String roleCode) {
        return user.getRoles().stream().anyMatch(role -> roleCode.equalsIgnoreCase(role.getCode()));
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record TaskOwner(UserEntity user, String expectedGroup) {
    }
}
