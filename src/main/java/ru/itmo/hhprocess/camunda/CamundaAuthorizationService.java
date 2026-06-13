package ru.itmo.hhprocess.camunda;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CamundaAuthorizationService {

    private static final String GROUP_CANDIDATE = "CANDIDATE";
    private static final String GROUP_RECRUITER = "RECRUITER";
    private static final String GROUP_ADMIN = "ADMIN";
    private static final String GROUP_CAMUNDA_ADMIN = "camunda-admin";
    private static final int RESOURCE_APPLICATION = 0;
    private static final int RESOURCE_AUTHORIZATION = 4;
    private static final int RESOURCE_PROCESS_DEFINITION = 6;
    private static final int RESOURCE_TASK = 7;
    private static final int RESOURCE_PROCESS_INSTANCE = 8;
    private static final int RESOURCE_DECISION_DEFINITION = 10;

    private final CamundaRestClient camundaRestClient;
    private final CamundaProperties properties;

    public void configureStartAuthorizations() {
        if (!properties.isEnabled()) {
            return;
        }

        ensureGroup(GROUP_CANDIDATE, "Candidates");
        ensureGroup(GROUP_RECRUITER, "Recruiters");
        ensureGroup(GROUP_ADMIN, "Administrators");
        ensureSystemGroup(GROUP_CAMUNDA_ADMIN, "camunda BPM Administrators");
        ensureAdminUser();

        configureWebAppAccess();
        grantStart(GROUP_CANDIDATE, properties.getApplicationProcessKey());
        grantStart(GROUP_CANDIDATE, "hhUiCandidateVacancyList");
        grantStart(GROUP_CANDIDATE, "hhUiCandidateApplicationList");
        grantStart(GROUP_CANDIDATE, "hhUiCandidateApplicationView");
        grantStart(GROUP_CANDIDATE, "hhUiNotificationList");
        grantStart(GROUP_RECRUITER, properties.getVacancyProcessKey());
        grantStart(GROUP_RECRUITER, properties.getVacancyStatusUpdateProcessKey());
        grantStart(GROUP_RECRUITER, properties.getRecruiterInterviewCancelProcessKey());
        grantStart(GROUP_RECRUITER, "hhUiRecruiterVacancyList");
        grantStart(GROUP_RECRUITER, "hhUiRecruiterApplicationList");
        grantStart(GROUP_RECRUITER, "hhUiRecruiterApplicationView");
        grantStart(GROUP_RECRUITER, "hhUiRecruiterSchedule");
        grantStart(GROUP_RECRUITER, "hhUiNotificationList");

        for (String processKey : List.of(
                properties.getApplicationProcessKey(),
                properties.getVacancyProcessKey(),
                properties.getAdminInterviewResetProcessKey(),
                properties.getTimeoutSchedulerProcessKey(),
                properties.getVacancyStatusUpdateProcessKey(),
                properties.getRecruiterInterviewCancelProcessKey(),
                "hhUiCandidateVacancyList",
                "hhUiCandidateApplicationList",
                "hhUiCandidateApplicationView",
                "hhUiRecruiterVacancyList",
                "hhUiRecruiterApplicationList",
                "hhUiRecruiterApplicationView",
                "hhUiRecruiterSchedule",
                "hhUiNotificationList",
                "hhUiAdminTimeoutReview")) {
            grantStart(GROUP_ADMIN, processKey);
        }
    }

    private void ensureGroup(String groupId, String groupName) {
        if (camundaRestClient.ensureGroupExists(groupId, groupName)) {
            log.info("Camunda group is available: {}", groupId);
        }
    }

    private void grantStart(String groupId, String processKey) {
        if (camundaRestClient.ensureProcessStartAuthorization(groupId, processKey)) {
            log.info("Camunda start authorization is available: group={}, processKey={}", groupId, processKey);
        }
    }

    private void ensureSystemGroup(String groupId, String groupName) {
        if (camundaRestClient.ensureGroupExists(groupId, groupName, "SYSTEM")) {
            log.info("Camunda system group is available: {}", groupId);
        }
    }

    private void ensureAdminUser() {
        camundaRestClient.ensureUserExists("admin", "admin@localhost", "Admin", "User", "admin", true);
        camundaRestClient.ensureMembershipExists("admin", GROUP_CAMUNDA_ADMIN);
    }

    private void configureWebAppAccess() {
        for (String group : List.of(GROUP_CANDIDATE, GROUP_RECRUITER, GROUP_ADMIN, GROUP_CAMUNDA_ADMIN)) {
            grant(group, RESOURCE_APPLICATION, "tasklist", List.of("ACCESS"));
            grant(group, RESOURCE_PROCESS_DEFINITION, "*", List.of("CREATE_INSTANCE", "READ"));
        }
        for (String group : List.of(GROUP_ADMIN, GROUP_CAMUNDA_ADMIN)) {
            grant(group, RESOURCE_APPLICATION, "cockpit", List.of("ACCESS"));
            grant(group, RESOURCE_APPLICATION, "admin", List.of("ACCESS"));
            grant(group, RESOURCE_TASK, "*", List.of("READ", "UPDATE", "TASK_WORK"));
            grant(group, RESOURCE_PROCESS_INSTANCE, "*", List.of("READ", "UPDATE"));
            grant(group, RESOURCE_DECISION_DEFINITION, "*", List.of("READ"));
            grant(group, RESOURCE_AUTHORIZATION, "*", List.of("READ", "CREATE", "UPDATE", "DELETE"));
        }
    }

    private void grant(String groupId, int resourceType, String resourceId, List<String> permissions) {
        if (camundaRestClient.ensureGroupAuthorization(groupId, resourceType, resourceId, permissions)) {
            log.info("Camunda authorization is available: group={}, resourceType={}, resourceId={}, permissions={}",
                    groupId, resourceType, resourceId, permissions);
        }
    }
}
